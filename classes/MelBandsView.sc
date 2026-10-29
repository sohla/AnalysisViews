// MelBandsView - a live mel-band level display.
//
// The "right now" counterpart to ScrollSpectrogram: that one puts time on the
// x axis, this one puts frequency there and shows the current frame only.
// Same data path as ScrollSpectrogram(source: \mel) - FluidMelBands.kr writes
// dB to a control bus, the language reads it through shared memory with
// Bus:-getnSynchronous (~0.002 ms a read, no OSC round trip), so it will run
// at whatever frame rate the view can repaint.
//
// Needs FluCoMa, and a server on localhost (shared memory is local only).
//
//   m = MelBandsView.new;                         // own window
//   m.start;
//
//   m = MelBandsView(numBands: 64, style: \area, colorMode: \freq);
//   m.start;
//
//   m.map_(\magma);          m.colorMode_(\level);   // \level | \freq | \flat
//   m.style_(\bars);         // \bars | \line | \area
//   m.dbRange_(-80, 0);
//   m.fps_(60);
//   m.showPeaks_(true);      m.peakHold_(1.5); m.peakDecay_(18);
//   m.attack_(0.01);         m.release_(0.25);       // bar ballistics, seconds
//   m.freqRange_(40, 16000);
//   m.numBands_(96);
//   m.stop;  m.close;
//
// Click and drag in the view for a band frequency / dB readout.

MelBandsView {

	classvar <>defaultMap = \viridis;

	// --- source
	var <numBands, <minFreq, <maxFreq, <bufSize, <inBus, <server;
	var melBus, melSynth, task, <isRunning = false;

	// --- data
	var values, peaks, peakAges, bandFreqs;
	var <attack = 0.01, <release = 0.25, attackCoeff, releaseCoeff;
	var <peakHold = 1.5, <peakDecay = 18;     // seconds, dB per second

	// --- look
	var <dbFloor = -80, <dbCeil = 0, <map, <style = \bars, <colorMode = \level;
	var <barColor, <background, <>gridColor, <>textColor, <>font;
	var <fps = 45, <showAxis = true, <showGrid = true, <showPeaks = true;
	var <gap = 1;                              // pixels between bars

	// --- gui
	var <window, <userview, ownsWindow = false;
	var mouseX, mouseY, showCursor = false, cmdPeriodFunc;

	*new { arg parent, bounds, numBands = 48, minFreq = 30, maxFreq = 18000,
		bufSize = 2048, inBus = 0, map, style = \bars, colorMode = \level,
		dbFloor = -80, dbCeil = 0, fps = 45, server;

		^super.new.init(parent, bounds, numBands, minFreq, maxFreq, bufSize,
			inBus, map, style, colorMode, dbFloor, dbCeil, fps, server);
	}

	init { arg parent, bounds, numBandsArg, minFreqArg, maxFreqArg, bufSizeArg,
		inBusArg, mapArg, styleArg, colorModeArg, dbFloorArg, dbCeilArg,
		fpsArg, serverArg;

		server = serverArg ? Server.default;
		numBands = numBandsArg;
		minFreq = minFreqArg;
		maxFreq = maxFreqArg;
		bufSize = bufSizeArg;
		inBus = inBusArg;
		style = styleArg;
		colorMode = colorModeArg;
		dbFloor = dbFloorArg;
		dbCeil = dbCeilArg;
		fps = fpsArg;
		map = SpecColorMap(mapArg ? defaultMap);

		background = Color.grey(0.08);
		barColor = Color(0.3, 0.8, 0.9);
		gridColor = Color.white.alpha_(0.14);
		textColor = Color.grey(0.7);
		font = Font(Font.defaultSansFace, 9);

		if(\FluidMelBands.asClass.isNil) {
			Error("MelBandsView needs FluidMelBands (FluCoMa). Install the "
				"FluidCorpusManipulation package, or use "
				"ScrollSpectrogram(source: \\fft) instead.").throw;
		};

		this.prBuildBands;
		this.prBuildCoeffs;
		this.prBuildGui(parent, bounds);
		^this
	}

	// --- mel helpers. Deliberately duplicated from ScrollSpectrogram rather
	// --- than cross-referenced, so the two classes stay independent.
	*hzToMel { arg hz; ^(2595 * log10(1 + (hz / 700))) }
	*melToHz { arg mel; ^(700 * ((10 ** (mel / 2595)) - 1)) }

	// FluidMelBands spaces band centres evenly on the mel scale.
	prBuildBands {
		var lo = this.class.hzToMel(minFreq), hi = this.class.hzToMel(maxFreq);
		bandFreqs = numBands.collect { |i|
			this.class.melToHz(lo.blend(hi, i / (numBands - 1).max(1)))
		};
		values   = FloatArray.fill(numBands, dbFloor);
		peaks    = FloatArray.fill(numBands, dbFloor);
		peakAges = FloatArray.fill(numBands, 0);
	}

	// one-pole ballistics, recomputed whenever fps or the times change
	prBuildCoeffs {
		attackCoeff  = exp(-1 / (attack.max(1e-4) * fps));
		releaseCoeff = exp(-1 / (release.max(1e-4) * fps));
	}

	// =====================================================================
	// source
	// =====================================================================

	prSendDef {
		SynthDef(\melBandsView, { |inbus = 0, out = 0, lo = 30, hi = 18000|
			Out.kr(out, FluidMelBands.kr(InFeedback.ar(inbus),
				numBands: numBands, minFreq: lo, maxFreq: hi,
				normalize: 1, scale: 1,            // scale 1 == dB out
				windowSize: bufSize, maxNumBands: numBands));
		}).send(server);
	}

	start {
		if(isRunning) { ^this };
		if(server.serverRunning.not) {
			"MelBandsView: server is not running.".warn;
			^this
		};
		if(server.hasShmInterface.not) {
			"MelBandsView: no shared-memory interface - the server must be on "
			"localhost.".warn;
			^this
		};

		isRunning = true;
		this.prSendDef;
		cmdPeriodFunc = cmdPeriodFunc ?? { { this.prRestart } };
		CmdPeriod.add(cmdPeriodFunc);

		melBus = Bus.control(server, numBands);
		task = Task({
			server.sync;
			melSynth = Synth(\melBandsView,
				[\inbus, inBus, \out, melBus, \lo, minFreq, \hi, maxFreq], server);
			0.2.wait;
			{
				this.prFrame(melBus.getnSynchronous(numBands));
				this.refresh;
				fps.reciprocal.wait;
			}.loop;
		}).play(AppClock);
	}

	prRestart {
		if(isRunning) {
			this.stop;
			AppClock.sched(0.6, { this.start; nil });
		};
	}

	stop {
		isRunning = false;
		task.stop; task = nil;
		if(cmdPeriodFunc.notNil) { CmdPeriod.remove(cmdPeriodFunc) };
		try { melSynth.free }; melSynth = nil;
		try { melBus.free };  melBus = nil;
	}

	// =====================================================================
	// per frame
	// =====================================================================

	prFrame { arg raw;
		var dt = fps.reciprocal, decayStep = peakDecay * dt;
		numBands.do { |i|
			var target = raw[i], v = values[i], coeff;
			if(target.isNil or: { target.isNaN }) { target = dbFloor };
			target = target.max(dbFloor - 10);
			// fast attack, slower release, so transients read but bars settle
			coeff = if(target > v) { attackCoeff } { releaseCoeff };
			v = target + ((v - target) * coeff);
			values[i] = v;

			if(v >= peaks[i]) {
				peaks[i] = v;
				peakAges[i] = 0;
			} {
				peakAges[i] = peakAges[i] + dt;
				if(peakAges[i] > peakHold) {
					peaks[i] = (peaks[i] - decayStep).max(dbFloor - 10);
				};
			};
		};
	}

	// =====================================================================
	// drawing
	// =====================================================================

	prNorm { arg db; ^((db - dbFloor) / (dbCeil - dbFloor).max(1e-9)).clip(0, 1) }

	prPlotRect { arg b;
		var left = if(showAxis) { 34 } { 0 };
		var bottom = if(showAxis) { 15 } { 0 };
		^Rect(left, 4, b.width - left - 4, b.height - bottom - 4);
	}

	prBandColor { arg i, norm;
		^switch(colorMode,
			\level, { map.at(norm) },
			\freq,  { map.at(i / (numBands - 1).max(1)) },
			\flat,  { barColor },
			{ barColor }
		);
	}

	prDraw { arg v;
		var r = this.prPlotRect(v.bounds);
		var w = r.width / numBands;

		Pen.use {
			Pen.addRect(r); Pen.clip;
			if(showGrid) { this.prDrawGrid(r) };

			switch(style,
				\bars, {
					numBands.do { |i|
						var n = this.prNorm(values[i]);
						var h = n * r.height;
						if(h > 0.5) {
							Pen.color = this.prBandColor(i, n);
							Pen.addRect(Rect(r.left + (i * w), r.bottom - h,
								(w - gap).max(1), h));
							Pen.fill;
						};
					};
				},
				\line, {
					Pen.width = 1.5;
					Pen.color = this.prBandColor(numBands.div(2), 0.8);
					numBands.do { |i|
						var p = Point(r.left + (i * w) + (w / 2),
							r.bottom - (this.prNorm(values[i]) * r.height));
						if(i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
					};
					Pen.stroke;
				},
				\area, {
					Pen.moveTo(Point(r.left, r.bottom));
					numBands.do { |i|
						Pen.lineTo(Point(r.left + (i * w) + (w / 2),
							r.bottom - (this.prNorm(values[i]) * r.height)));
					};
					Pen.lineTo(Point(r.right, r.bottom));
					Pen.lineTo(Point(r.left, r.bottom));
					Pen.fillAxialGradient(
						Point(r.left, r.top), Point(r.left, r.bottom),
						map.at(0.9), map.at(0.1));
				}
			);

			if(showPeaks) {
				Pen.width = 1;
				numBands.do { |i|
					var n = this.prNorm(peaks[i]);
					var y = r.bottom - (n * r.height);
					if(n > 0.001) {
						Pen.color = this.prBandColor(i, n).blend(Color.white, 0.5);
						Pen.line(Point(r.left + (i * w), y),
							Point(r.left + (i * w) + (w - gap).max(1), y));
					};
				};
				Pen.stroke;
			};
		};

		if(showAxis) { this.prDrawAxis(v.bounds, r) };
		if(showCursor) { this.prDrawCursor(r) };
	}

	prDbTicks {
		var step = case
			{ (dbCeil - dbFloor) > 90 } { 20 }
			{ (dbCeil - dbFloor) > 40 } { 10 }
			{ 5 };
		^(dbFloor.roundUp(step), dbFloor.roundUp(step) + step .. dbCeil);
	}

	// label a handful of round frequencies, positioned at their real band
	prFreqTicks {
		^[50, 100, 200, 500, 1000, 2000, 5000, 10000, 15000]
			.select { |f| f >= minFreq and: { f <= maxFreq } };
	}

	prFreqToX { arg hz, r;
		var lo = this.class.hzToMel(minFreq), hi = this.class.hzToMel(maxFreq);
		var pos = (this.class.hzToMel(hz) - lo) / (hi - lo).max(1e-9);
		^(r.left + (pos * r.width))
	}

	prDrawGrid { arg r;
		Pen.color = gridColor;
		Pen.width = 1;
		this.prDbTicks.do { |db|
			var y = r.bottom - (this.prNorm(db) * r.height);
			Pen.line(Point(r.left, y), Point(r.right, y));
		};
		this.prFreqTicks.do { |f|
			var x = this.prFreqToX(f, r);
			Pen.line(Point(x, r.top), Point(x, r.bottom));
		};
		Pen.stroke;
	}

	prFreqLabel { arg f;
		^if(f >= 1000) { (f / 1000).round(0.1).asString ++ "k" } { f.asInteger.asString }
	}

	prDrawAxis { arg b, r;
		this.prDbTicks.do { |db|
			var y = r.bottom - (this.prNorm(db) * r.height);
			Pen.stringRightJustIn(db.asInteger.asString,
				Rect(0, y - 6, r.left - 4, 12), font, textColor);
		};
		this.prFreqTicks.do { |f|
			var x = this.prFreqToX(f, r);
			Pen.stringAtPoint(this.prFreqLabel(f),
				Point(x - 8, r.bottom + 2), font, textColor);
		};
	}

	prDrawCursor { arg r;
		var i, label, hz, db;
		if(mouseX.isNil or: { r.width <= 0 }) { ^this };
		i = (((mouseX - r.left) / r.width) * numBands).floor.asInteger
			.clip(0, numBands - 1);
		hz = bandFreqs[i];
		db = values[i];

		Pen.use {
			Pen.addRect(r); Pen.clip;
			Pen.color = Color.white.alpha_(0.25);
			Pen.addRect(Rect(r.left + (i * (r.width / numBands)), r.top,
				(r.width / numBands).max(1), r.height));
			Pen.fill;
		};
		label = "band %  % Hz  % dB".format(i, hz.round(1), db.round(0.1));
		Pen.color = Color.black.alpha_(0.75);
		Pen.addRect(Rect(r.left + 4, r.top + 2, 160, 14));
		Pen.fill;
		Pen.stringAtPoint(label, Point(r.left + 7, r.top + 3), font, Color.white);
	}

	// =====================================================================
	// gui
	// =====================================================================

	prBuildGui { arg parent, bounds;
		var b;
		if(parent.isNil) {
			ownsWindow = true;
			window = Window("MelBandsView",
				bounds ?? { Rect(160, 300, 760, 300) }, scroll: false);
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
				{ ch == $p } { this.showPeaks_(showPeaks.not) }
				{ ch == $g } { this.showGrid_(showGrid.not) }
				{ ch == $r } { this.resetPeaks };
			});

		if(ownsWindow) {
			window.onClose_({ this.stop }).front;
		};
	}

	refresh { if(userview.notNil and: { userview.notClosed }) { userview.refresh } }

	close { this.stop; if(ownsWindow and: { window.notNil }) { window.close } }

	// =====================================================================
	// configuration (all live)
	// =====================================================================

	map_ { arg m; map = SpecColorMap(m); this.refresh }
	style_ { arg s; style = s; this.refresh }
	colorMode_ { arg m; colorMode = m; this.refresh }
	barColor_ { arg c; barColor = c; this.refresh }
	background_ { arg c; background = c; userview !? { userview.background_(c) }; this.refresh }
	gap_ { arg px; gap = px.max(0); this.refresh }

	dbFloor_ { arg db; dbFloor = db; this.refresh }
	dbCeil_ { arg db; dbCeil = db; this.refresh }
	dbRange_ { arg lo, hi = 0; dbFloor = lo; dbCeil = hi; this.refresh }

	fps_ { arg f; fps = f.clip(1, 120); this.prBuildCoeffs }
	attack_ { arg sec; attack = sec; this.prBuildCoeffs }
	release_ { arg sec; release = sec; this.prBuildCoeffs }

	showPeaks_ { arg bool; showPeaks = bool; this.refresh }
	showGrid_ { arg bool; showGrid = bool; this.refresh }
	showAxis_ { arg bool; showAxis = bool; this.refresh }
	peakHold_ { arg sec; peakHold = sec }
	peakDecay_ { arg dbPerSec; peakDecay = dbPerSec }
	resetPeaks { numBands.do { |i| peaks[i] = dbFloor; peakAges[i] = 0 }; this.refresh }

	inBus_ { arg b; inBus = b; melSynth !? { melSynth.set(\inbus, b) } }

	numBands_ { arg n;
		var wasRunning = isRunning;
		numBands = n.clip(4, 512);
		if(wasRunning) { this.stop };
		this.prBuildBands;
		if(wasRunning) { this.start };
		this.refresh;
	}

	freqRange_ { arg lo, hi;
		minFreq = lo.max(1);
		maxFreq = hi.min((server.sampleRate ? 44100) / 2);
		this.prBuildBands;
		melSynth !? { melSynth.set(\lo, minFreq, \hi, maxFreq) };
		this.refresh;
	}

	// --- inspection ---

	bandFreqs { ^bandFreqs.copy }
	levels { ^values.copy }
	peakLevels { ^peaks.copy }

	info {
		"MelBandsView\n"
		"  bands  : % from % to % Hz (window %)\n"
		"  dB     : % .. %\n"
		"  look   : style % colour % map %\n"
		"  timing : % fps, attack % s, release % s, peak hold % s decay % dB/s\n".postf(
			numBands, minFreq.round(0.1), maxFreq.round(0.1), bufSize,
			dbFloor, dbCeil, style, colorMode, map.stops.size,
			fps, attack, release, peakHold, peakDecay);
	}
}
