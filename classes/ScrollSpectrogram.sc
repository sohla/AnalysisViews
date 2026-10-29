// OPTION 2 / 3 - a new scrolling spectrogram, built for configurability.
//
// One renderer, two data paths (pick with `source:`):
//
//   \fft  (option 2)  FFT UGen -> Buffer -> Buffer:-getn -> OSC -> language.
//                     Same path as the Spectrogram quark. Raw linear bins, so
//                     you get real FFT detail; costs an OSC round trip a frame.
//
//   \mel  (option 3)  FluidMelBands.kr -> control bus -> shared memory.
//                     Read with Bus:-getnSynchronous: no OSC, no async
//                     callback, no jitter. Band count is independent of FFT
//                     size, so you can run a 4096-point window and still only
//                     move 128 numbers per frame. Needs FluCoMa + a server on
//                     localhost. Bands are mel-spaced, so the picture is
//                     perceptually scaled rather than raw bins.
//
// Measured on this machine (44.1k, block 512):
//   \fft : a /b_getn round trip costs ~11.5 ms and that is LATENCY, not
//          bandwidth - 512, 1024 and 2048 floats all returned at ~87/s. So
//          this path tops out near 87 fps whatever the window size, and 30 fps
//          sits comfortably inside it.
//   \mel : a shared-memory read costs ~0.002 ms, i.e. ~400,000/s. Transfer is
//          free; frame rate is bounded only by how fast the view redraws.
//
// A calibration note. On \fft the dB readout is true dBFS: a 0.3-amplitude
// sine (-10.5 dBFS) measured -10.5 at bufSize 4096 and -11.6 at 2048, the
// difference being bin-straddling scalloping loss. On \mel the same tone
// measured -14.9, because a normalised triangle filter spreads one tone across
// its band. Treat \mel levels as a perceptual display, not absolute dBFS -
// trim with dbOffset_ if you need the two paths to agree.
//
// What this fixes relative to the quark:
//   * arbitrary colour gradients, 512-entry LUT (quark: 17 grey steps)
//   * true dB floor / ceiling / gamma in dBFS, ~120 dB available
//     (quark: a fixed 64 dB window that bottoms out at -48 dBFS @ bufSize 1024)
//   * log / mel / linear frequency axis, row count decoupled from bin count
//   * time span in seconds and frame rate both settable, live
//   * resizing the window rescales instead of wiping the history
//   * frequency + dB readout under the cursor, axis labels, gridlines
//
//   ScrollSpectrogram.new;                                  // defaults, own window
//   ScrollSpectrogram(source: \mel, numBands: 192);          // shm path
//   z = ScrollSpectrogram(map: \magma, seconds: 12, freqScale: \log);
//   z.map_(\turbo); z.dbRange_(-90, -10); z.gamma_(0.7); z.fps_(45);
//   z.freqRange_(40, 16000); z.seconds_(30); z.freeze; z.snapshot;

ScrollSpectrogram {

	classvar <>defaultMap = \viridis;

	// --- data source
	var <source, <inBus, <bufSize, <numBands;
	var fftBuf, fftSynth, <feed, <server;
	var fftOsc, rawBuf, fftLo, fftCount, fftChunks, chunksPending = 0;
	var fftInFlight = false, lastRequest = 0;

	// --- analysis -> row mapping
	var <numRows, <freqScale, <minFreq, <maxFreq;
	var binToRow, rowFill, <rowFreqs, srcFreqs, numSrc;

	// --- tone mapping
	var <map, <dbFloor = -90, <dbCeil = -6, <gamma = 1.0, <dbOffset;
	var lut, lutSize = 512;

	// --- image / time
	var image, <numCols, <seconds = 10, <fps = 30, <writeX = 0;
	var colPixels, rowMax, history;

	// --- gui
	var <window, <userview, <background, <>gridColor, <>textColor;
	var <showAxis = true, <showGrid = true, <>font;
	var mouseX, mouseY, showCursor = false;

	// --- state
	var task, <isRunning = false, <isFrozen = false, cmdPeriodFunc;

	*new { arg parent, bounds, source = \fft, map, seconds = 10, fps = 30,
		bufSize = 2048, numBands = 128, numRows, freqScale = \log,
		minFreq = 30, maxFreq, inBus = 0, dbFloor = -90, dbCeil = -6, server;

		^super.new.init(parent, bounds, source, map, seconds, fps, bufSize,
			numBands, numRows, freqScale, minFreq, maxFreq, inBus,
			dbFloor, dbCeil, server);
	}

	init { arg parent, bounds, sourceArg, mapArg, secondsArg, fpsArg, bufSizeArg,
		numBandsArg, numRowsArg, freqScaleArg, minFreqArg, maxFreqArg, inBusArg,
		dbFloorArg, dbCeilArg, serverArg;

		server = serverArg ? Server.default;
		source = sourceArg;
		inBus = inBusArg;
		bufSize = bufSizeArg;
		numBands = numBandsArg;
		seconds = secondsArg;
		fps = fpsArg;
		freqScale = freqScaleArg;
		minFreq = minFreqArg;
		maxFreq = maxFreqArg ? ((server.sampleRate ? 44100) / 2);
		dbFloor = dbFloorArg;
		dbCeil = dbCeilArg;
		map = SpecColorMap(mapArg ? defaultMap);
		background = Color.black;
		gridColor = Color.white.alpha_(0.18);
		textColor = Color.grey(0.75);
		font = Font(Font.defaultSansFace, 9);
		dbOffset = this.prDefaultDbOffset;

		if(source == \mel and: { \FluidMelBands.asClass.isNil }) {
			"ScrollSpectrogram: FluidMelBands not found, falling back to source: \\fft".warn;
			source = \fft;
		};

		numSrc = this.prNumSourceValues;
		numRows = numRowsArg ? numSrc.clip(64, 512);
		numCols = (seconds * fps).asInteger.max(16);

		this.prBuildMaps;
		this.prBuildLut;
		this.prBuildImage;
		this.prBuildGui(parent, bounds);
		^this
	}

	// =====================================================================
	// analysis -> row mapping
	// =====================================================================

	prNumSourceValues {
		^if(source == \mel) { numBands } { bufSize.div(2) }
	}

	// Centre frequency of each incoming analysis value.
	prSourceFreqs {
		var sr = server.sampleRate ? 44100;
		^if(source == \mel) {
			// FluidMelBands spaces band centres evenly on the mel scale.
			var lo = this.class.hzToMel(minFreq), hi = this.class.hzToMel(maxFreq);
			numBands.collect { |i|
				this.class.melToHz(lo.blend(hi, i / (numBands - 1).max(1)))
			};
		} {
			bufSize.div(2).collect { |i| i * sr / bufSize };
		};
	}

	*hzToMel { arg hz; ^(2595 * log10(1 + (hz / 700))) }
	*melToHz { arg mel; ^(700 * ((10 ** (mel / 2595)) - 1)) }

	// position 0..1 of a frequency on the current vertical scale
	prFreqPos { arg hz;
		^switch(freqScale,
			\lin, { (hz - minFreq) / (maxFreq - minFreq) },
			\log, { log(hz.max(1e-6) / minFreq) / log(maxFreq / minFreq) },
			\mel, {
				var lo = this.class.hzToMel(minFreq), hi = this.class.hzToMel(maxFreq);
				(this.class.hzToMel(hz) - lo) / (hi - lo)
			},
			{ (hz - minFreq) / (maxFreq - minFreq) }
		);
	}

	prPosFreq { arg pos;
		^switch(freqScale,
			\lin, { minFreq + (pos * (maxFreq - minFreq)) },
			\log, { minFreq * ((maxFreq / minFreq) ** pos) },
			\mel, {
				var lo = this.class.hzToMel(minFreq), hi = this.class.hzToMel(maxFreq);
				this.class.melToHz(lo.blend(hi, pos))
			},
			{ minFreq + (pos * (maxFreq - minFreq)) }
		);
	}

	// binToRow: for each incoming value, which image row it lands in (-1 = drop).
	// rowFill: for rows no value lands in (common at low frequencies on a log
	// axis), which row to copy from instead - precomputed, so the per-frame
	// loop is a single pass over the source values plus a single pass over rows.
	prBuildMaps {
		var nSrc, lastSeen;
		srcFreqs = this.prSourceFreqs;
		nSrc = srcFreqs.size;
		numSrc = nSrc;

		// row 0 is the top of the display = maxFreq
		rowFreqs = numRows.collect { |r|
			this.prPosFreq(1 - (r / (numRows - 1).max(1)))
		};

		binToRow = Int32Array.fill(nSrc, -1);
		nSrc.do { |i|
			var pos = this.prFreqPos(srcFreqs[i]);
			if(pos >= 0 and: { pos <= 1 }) {
				binToRow[i] = ((1 - pos) * (numRows - 1)).round.asInteger
					.clip(0, numRows - 1);
			};
		};

		// which rows actually receive data
		rowFill = Int32Array.fill(numRows, -1);
		binToRow.do { |r| if(r >= 0) { rowFill[r] = r } };
		// fill gaps downward from the nearest populated row above
		lastSeen = -1;
		numRows.do { |r|
			if(rowFill[r] >= 0) { lastSeen = r } { rowFill[r] = lastSeen };
		};
		// and any leading gap upward
		lastSeen = -1;
		(numRows - 1).to(0, -1).do { |r|
			if(rowFill[r] >= 0) { lastSeen = rowFill[r] } { rowFill[r] = lastSeen };
		};

		rowMax = FloatArray.newClear(numRows);
		colPixels = Int32Array.newClear(numRows);
	}

	// dBFS offset: a full-scale sinusoid through a Hann-windowed N-point FFT
	// peaks at magnitude N/4, so subtract that to read out in dBFS.
	prDefaultDbOffset {
		^if(source == \mel) { 0 } { 20 * log10(bufSize / 4) }
	}

	// =====================================================================
	// colour
	// =====================================================================

	prBuildLut {
		map.gamma_(gamma);
		map.size_(lutSize);
		lut = map.asLut;
	}

	// =====================================================================
	// image
	// =====================================================================

	prBuildImage {
		var old = image, oldCols = numCols;
		numCols = (seconds * fps).asInteger.max(16);
		image = Image.color(numCols, numRows, background);
		history = FloatArray.fill(numCols * numRows, dbFloor - 1);
		writeX = 0;
		if(old.notNil) { old.free };
	}

	clear {
		image.fill(background);
		history.fill(dbFloor - 1);
		writeX = 0;
		this.refresh;
	}

	// =====================================================================
	// per-frame: analysis values (dB) -> one image column
	// =====================================================================

	// dbValues[i] is the dB of source value (i + offset). One pass over the
	// incoming values, one pass over the rows - no per-row searching.
	prPushColumn { arg dbValues, offset = 0;
		var floorDb = dbFloor, span = (dbCeil - dbFloor).max(1e-9), top = lutSize - 1;
		var base = writeX * numRows, n = dbValues.size;

		// pass 1: collapse source values into rows, keeping the maximum so
		// narrow peaks survive downsampling
		numRows.do { |r| rowMax[r] = -999.0 };
		n.do { |i|
			var r = binToRow[i + offset];
			if(r >= 0) {
				var v = dbValues[i];
				if(v > rowMax[r]) { rowMax[r] = v };
			};
		};

		// pass 2: fill rows no value landed in, then map through the colour LUT
		numRows.do { |r|
			var v = rowMax[r], srcRow;
			if(v < -998.0) {
				srcRow = rowFill[r];
				v = if(srcRow >= 0 and: { rowMax[srcRow] > -998.0 }) {
					rowMax[srcRow]
				} {
					floorDb
				};
			};
			history[base + r] = v;
			colPixels[r] = lut[(((v - floorDb) / span).clip(0, 1) * top).asInteger];
		};

		image.setPixels(colPixels, Rect(writeX, 0, 1, numRows));
		writeX = (writeX + 1) % numCols;
	}

	// =====================================================================
	// sources
	// =====================================================================

	prSendDefs {
		SynthDef(\scrollSpec_fft, { |inbus = 0, buffer = 0|
			FFT(buffer, InFeedback.ar(inbus), hop: 0.5, wintype: 1);
		}).send(server);
	}

	start {
		if(isRunning) { ^this };
		if(server.serverRunning.not) {
			"ScrollSpectrogram: server is not running.".warn;
			^this
		};
		if(source == \mel and: { server.hasShmInterface.not }) {
			"ScrollSpectrogram: no shared-memory interface (server must be on "
			"localhost). Falling back to source: \\fft.".warn;
			source = \fft;
			dbOffset = this.prDefaultDbOffset;
			this.prBuildMaps;
		};

		isRunning = true;
		this.prSendDefs;

		cmdPeriodFunc = cmdPeriodFunc ?? { { this.prRestart } };
		CmdPeriod.add(cmdPeriodFunc);

		switch(source,
			\fft, { this.prStartFft },
			\mel, { this.prStartMel },
			{ "ScrollSpectrogram: unknown source %".format(source).warn }
		);
	}

	prRestart {
		if(isRunning) {
			isRunning = false;
			task.stop;
			AppClock.sched(0.6, { this.start; nil });
		};
	}

	// Buffer:-getToFloatArray chunks at 1633 floats, commented "max size for
	// getn under udp". That ceiling is legacy: measured on this setup a
	// 2048-float /b_getn reply comes back fine, 40 of 40. It is not guaranteed
	// across setups though (smaller UDP limits, remote servers), so the request
	// is split anyway. The reason this is hand-rolled rather than a call to
	// getn/getToFloatArray is the responder - both build an OSCFunc per call,
	// and this runs every frame. One persistent OSCFunc instead.
	prStartFft {
		var chunk = 1632;   // even, so re/im pairs stay aligned

		fftLo = 1;                                   // skip the DC/Nyquist slot
		fftCount = (bufSize.div(2) - 1) * 2;
		fftChunks = (fftCount / chunk).roundUp.asInteger;
		rawBuf = FloatArray.newClear(fftCount);
		fftInFlight = false;
		chunksPending = 0;

		fftBuf = Buffer.alloc(server, bufSize);

		fftOsc = OSCFunc({ |msg|
			if(fftBuf.notNil and: { msg[1] == fftBuf.bufnum }) {
				rawBuf = rawBuf.overWrite(
					FloatArray.newFrom(msg.copyToEnd(4)), msg[2] - (fftLo * 2));
				chunksPending = chunksPending - 1;
				if(chunksPending <= 0) {
					fftInFlight = false;
					this.prFftFrame(rawBuf);
				};
			};
		}, '/b_setn', server.addr);

		task = Task({
			server.sync;
			fftSynth = Synth(\scrollSpec_fft,
				[\inbus, inBus, \buffer, fftBuf], server);
			0.2.wait;
			{
				// drop a stuck frame rather than wedging the display
				if(fftInFlight and: { (Main.elapsedTime - lastRequest) > 0.5 }) {
					fftInFlight = false;
				};
				if(isFrozen.not and: { fftInFlight.not }) {
					var pos = fftLo * 2, left = fftCount, msgs = Array.new(fftChunks);
					while { left > 0 } {
						var n = min(chunk, left);
						msgs = msgs.add(fftBuf.getnMsg(pos, n));
						pos = pos + n;
						left = left - n;
					};
					chunksPending = msgs.size;
					fftInFlight = true;
					lastRequest = Main.elapsedTime;
					server.listSendBundle(nil, msgs);
				};
				fps.reciprocal.wait;
			}.loop;
		}).play(AppClock);
	}

	prFftFrame { arg raw;
		var pair, db;
		// [re, im] interleaved -> two Signals. Complex:-magnitude measured
		// faster than unpacking the pairs by hand in sclang, so leave it alone.
		pair = raw.clump(2).flop;
		db = Complex(Signal.newFrom(pair[0]), Signal.newFrom(pair[1])).magnitude;
		db = (db.max(1e-9).log10 * 20) - dbOffset;
		{
			if(isRunning and: { isFrozen.not }) {
				this.prPushColumn(db, fftLo);
				this.refresh;
			};
		}.defer;
	}

	prStartMel {
		// shared with any other view asking for the same analysis, so a
		// spectrogram and a bands view side by side cost one STFT, not two
		feed = SpecAnalysisFeed.melBands(server, inBus, numBands,
			minFreq, maxFreq, bufSize);
		task = Task({
			var vals;
			0.2.wait;
			{
				if(isFrozen.not) {
					// shared memory: synchronous, no OSC round trip
					vals = feed.read;
					if(vals.notNil) { this.prPushColumn(vals); this.refresh };
				};
				fps.reciprocal.wait;
			}.loop;
		}).play(AppClock);
	}

	stop {
		isRunning = false;
		task.stop;
		if(cmdPeriodFunc.notNil) { CmdPeriod.remove(cmdPeriodFunc) };
		try { fftOsc.free };  fftOsc = nil;
		try { fftSynth.free }; fftSynth = nil;

		try { fftBuf.free };  fftBuf = nil;
		feed !? { feed.release }; feed = nil;
		fftInFlight = false; chunksPending = 0;
	}

	freeze { isFrozen = true }
	unfreeze { isFrozen = false }
	toggleFreeze { isFrozen = isFrozen.not; ^isFrozen }

	// =====================================================================
	// configuration (all live)
	// =====================================================================

	map_ { arg m; map = SpecColorMap(m, gamma: gamma); this.prBuildLut; this.refresh }
	gamma_ { arg g; gamma = g; this.prBuildLut; this.refresh }
	dbFloor_ { arg db; dbFloor = db; this.refresh }
	dbCeil_ { arg db; dbCeil = db; this.refresh }
	dbRange_ { arg lo, hi = -6; dbFloor = lo; dbCeil = hi; this.refresh }
	dbOffset_ { arg db; dbOffset = db }
	inBus_ { arg b;
		if(source == \mel) {
			this.prReacquire({ inBus = b });
		} {
			inBus = b;
			fftSynth !? { fftSynth.set(\inbus, b) };
		};
	}

	// the \mel feed is keyed on its parameters, so changing one swaps feeds
	prReacquire { arg changes;
		var wasRunning = isRunning;
		if(wasRunning) { this.stop };
		changes.value;
		if(wasRunning) { this.start };
	}

	background_ { arg c; background = c; this.clear }

	fps_ { arg f; fps = f.clip(1, 120); this.prBuildImage; this.refresh }
	seconds_ { arg sec; seconds = sec.max(0.5); this.prBuildImage; this.refresh }

	freqScale_ { arg scale;
		freqScale = scale;
		this.prBuildMaps;
		this.clear;
	}

	freqRange_ { arg lo, hi;
		this.prReacquire({
			minFreq = lo.max(1);
			maxFreq = hi.min((server.sampleRate ? 44100) / 2);
			this.prBuildMaps;
		});
		this.clear;
	}

	numRows_ { arg n;
		numRows = n.clip(16, 2048);
		this.prBuildMaps;
		this.prBuildImage;
		this.refresh;
	}

	showAxis_ { arg bool; showAxis = bool; this.refresh }
	showGrid_ { arg bool; showGrid = bool; this.refresh }

	// =====================================================================
	// gui
	// =====================================================================

	prBuildGui { arg parent, bounds;
		var b;
		if(parent.isNil) {
			window = Window("ScrollSpectrogram", bounds ?? { Rect(140, 320, 900, 420) },
				scroll: false);
			b = window.view.bounds;
		} {
			window = parent;
			// A view's bounds are relative to its PARENT, so a CompositeView
			// sitting at y=405 in a window reports Rect(0, 405, w, h). Using
			// that as a child's bounds places the child 405px down INSIDE a
			// container only h tall, i.e. off the bottom and invisible. Only
			// a pane at the origin survives. Take the parent's interior
			// instead, which always starts at 0,0.
			b = bounds ?? { parent.asView.bounds.moveTo(0, 0) };
		};

		userview = UserView(window, b)
			.resize_(5)
			.focusColor_(Color.clear)
			.background_(background)
			.drawFunc_({ |v| this.prDraw(v) })
			.mouseDownAction_({ |v, x, y| showCursor = true; mouseX = x; mouseY = y; v.refresh })
			.mouseMoveAction_({ |v, x, y| mouseX = x; mouseY = y; v.refresh })
			.mouseUpAction_({ |v| showCursor = false; v.refresh })
			.keyDownAction_({ |v, ch|
				case
				{ ch.ascii == 32 } { this.toggleFreeze }
				{ ch == $c } { this.clear }
				{ ch == $s } { this.snapshot };
			});

		if(window !== parent) {
			window.onClose_({ this.stop; image.free; image = nil }).front;
		};
	}

	// the plotting area inside the view (axis gutter removed)
	prPlotRect { arg viewBounds;
		var left = if(showAxis) { 44 } { 0 };
		var bottom = if(showAxis) { 14 } { 0 };
		^Rect(left, 0, viewBounds.width - left, viewBounds.height - bottom);
	}

	prDraw { arg v;
		var r = this.prPlotRect(v.bounds);
		var sx = r.width / numCols, older, nRecent;

		if(image.isNil) { ^this };

		Pen.use {
			Pen.addRect(r); Pen.clip;

			// ring buffer: draw the two slices in time order instead of
			// shifting every pixel each frame
			nRecent = writeX;
			older = numCols - nRecent;
			if(older > 0) {
				Pen.drawImage(
					Rect(r.left, r.top, older * sx, r.height),
					image, Rect(nRecent, 0, older, numRows));
			};
			if(nRecent > 0) {
				Pen.drawImage(
					Rect(r.left + (older * sx), r.top, nRecent * sx, r.height),
					image, Rect(0, 0, nRecent, numRows));
			};
		};

		if(showGrid) { this.prDrawGrid(r) };
		if(showAxis) { this.prDrawAxis(v.bounds, r) };
		if(showCursor) { this.prDrawCursor(v.bounds, r) };
		if(isFrozen) {
			Pen.color = Color.red.alpha_(0.9);
			Pen.stringAtPoint("FROZEN", r.left + 6 @ 4, font, Color.red);
		};
	}

	prFreqTicks {
		var ticks;
		^if(freqScale == \lin) {
			ticks = (0..10).collect { |i| minFreq.blend(maxFreq, i / 10) };
			ticks.select { |f| f >= minFreq and: { f <= maxFreq } };
		} {
			ticks = [20, 30, 50, 100, 200, 300, 500, 1000, 2000, 3000, 5000,
				10000, 15000, 20000];
			ticks.select { |f| f >= minFreq and: { f <= maxFreq } };
		};
	}

	prDrawGrid { arg r;
		Pen.use {
			Pen.addRect(r); Pen.clip;
			Pen.color = gridColor;
			Pen.width = 1;
			this.prFreqTicks.do { |f|
				var y = r.top + ((1 - this.prFreqPos(f)) * r.height);
				Pen.line(r.left @ y, r.right @ y);
			};
			// one vertical line per second
			seconds.floor.asInteger.do { |i|
				var x = r.right - ((i + 1) / seconds * r.width);
				if(x > r.left) { Pen.line(x @ r.top, x @ r.bottom) };
			};
			Pen.stroke;
		};
	}

	prFreqLabel { arg f;
		^if(f >= 1000) { (f / 1000).round(0.1).asString ++ "k" } { f.round(1).asString }
	}

	prDrawAxis { arg viewBounds, r;
		this.prFreqTicks.do { |f|
			var y = r.top + ((1 - this.prFreqPos(f)) * r.height);
			Pen.stringRightJustIn(this.prFreqLabel(f),
				Rect(0, y - 6, r.left - 5, 12), font, textColor);
		};
		Pen.stringAtPoint("-" ++ seconds.round(0.1).asString ++ "s",
			r.left + 2 @ (r.bottom + 1), font, textColor);
		Pen.stringRightJustIn("now",
			Rect(r.right - 40, r.bottom + 1, 38, 12), font, textColor);
		Pen.stringAtPoint(
			"% .. % dBFS  |  %  |  %".format(dbFloor.round(1), dbCeil.round(1),
				freqScale, source),
			r.left + 40 @ (r.bottom + 1), font, textColor);
	}

	// frequency and dB under the cursor, read back out of the history ring
	prDrawCursor { arg viewBounds, r;
		var pos, hz, row, col, db, label;
		if(mouseX.isNil or: { r.width <= 0 }) { ^this };
		pos = 1 - ((mouseY - r.top) / r.height);
		hz = this.prPosFreq(pos.clip(0, 1));
		row = ((1 - pos) * (numRows - 1)).round.asInteger.clip(0, numRows - 1);
		col = (((mouseX - r.left) / r.width) * numCols).floor.asInteger
			.clip(0, numCols - 1);
		col = (writeX + col) % numCols;
		db = history[(col * numRows) + row];

		Pen.use {
			Pen.addRect(r); Pen.clip;
			Pen.color = Color.white.alpha_(0.6);
			Pen.width = 1;
			Pen.line(r.left @ mouseY, r.right @ mouseY);
			Pen.line(mouseX @ r.top, mouseX @ r.bottom);
			Pen.stroke;
		};
		label = "% Hz   % dB".format(hz.round(0.1),
			if(db > (dbFloor - 0.5)) { db.round(0.1) } { "-inf" });
		Pen.color = Color.black.alpha_(0.7);
		Pen.addRect(Rect(mouseX + 8, mouseY - 18, 120, 14));
		Pen.fill;
		Pen.stringAtPoint(label, mouseX + 11 @ (mouseY - 17), font, Color.white);
	}

	refresh { if(userview.notNil and: { userview.notClosed }) { userview.refresh } }

	// =====================================================================
	// export / inspection
	// =====================================================================

	// Write the current history to a PNG, unrolled so time runs left to right.
	snapshot { arg path, scaleY = 1;
		var out, older = numCols - writeX;
		path = path ?? {
			Platform.userHomeDir +/+ "spectrogram-%.png".format(
				Date.getDate.stamp);
		};
		out = Image.color(numCols, numRows, background);
		out.draw({
			if(older > 0) {
				Pen.drawImage(Rect(0, 0, older, numRows), image,
					Rect(writeX, 0, older, numRows));
			};
			if(writeX > 0) {
				Pen.drawImage(Rect(older, 0, writeX, numRows), image,
					Rect(0, 0, writeX, numRows));
			};
		});
		out.write(path, \png);
		out.free;
		("ScrollSpectrogram: wrote " ++ path).postln;
		^path
	}

	// dB values of the most recent column, lowest row first
	latestColumn {
		var col = (writeX - 1) % numCols;
		^FloatArray.fill(numRows, { |r| history[(col * numRows) + r] });
	}

	info {
		"ScrollSpectrogram\n"
		"  source     : %  (% values in)\n"
		"  rows       : %   cols: %  (% s @ % fps)\n"
		"  freq       : % .. % Hz, % scale\n"
		"  dB         : % .. % dBFS  (offset %, gamma %)\n"
		"  colour map : % stops, % LUT entries\n".postf(
			source, numSrc, numRows, numCols, seconds, fps,
			minFreq.round(0.1), maxFreq.round(0.1), freqScale,
			dbFloor, dbCeil, dbOffset.round(0.1), gamma,
			map.stops.size, lutSize);
	}
}
