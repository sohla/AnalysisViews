// MFCCView - live Mel-Frequency Cepstral Coefficients.
//
// Two modes, switch live with mode_:
//   \scroll  a cepstrogram: coefficient index up the y axis, time along x,
//            diverging colour because coefficients are signed.
//   \bars    the current frame only, bipolar bars either side of a zero line.
//
// Same shared-memory path as MelBandsView - FluidMFCC.kr writes to a control
// bus, the language reads it with Bus:-getnSynchronous, no OSC per frame.
// Needs FluCoMa and a server on localhost.
//
// Two things about MFCCs that shape this view:
//
//   * Coefficient 0 is overall energy and dwarfs the rest, so it is normally
//     left out. FluidMFCC does that on the server with startCoeff, so this
//     defaults to startCoeff: 1 and you get coefficients 1..n. Set
//     startCoeff_(0) if you want the energy term back.
//   * Coefficient magnitudes depend heavily on material and on numBands, so
//     there is no sensible fixed scale. autoRange is on by default: it tracks
//     the running peak magnitude and decays slowly. range_(n) pins it instead.
//
//   f = MFCCView.new;  f.start;
//
//   f.mode_(\bars);  f.mode_(\scroll);
//   f.map_(\rdbu);   f.map_(\spectral);     // see SpecColorMap.divergingNames
//   f.numCoeffs_(20);
//   f.startCoeff_(0);                       // put the energy term back
//   f.range_(60);    f.autoRange_(true);
//   f.seconds_(20);  f.fps_(45);
//   f.coeffs;        // current frame as a FloatArray
//   f.stop;  f.close;

MFCCView {

	classvar <>defaultMap = \coolwarm;

	// --- source
	var <numCoeffs, <numBands, <startCoeff, <minFreq, <maxFreq, <bufSize;
	var <inBus, <server, <feed, task, <isRunning = false;

	// --- data
	var values, <range = 50, <autoRange = true, runningMax = 1;
	var <autoDecay = 0.3;          // how fast the auto range relaxes, per second

	// --- scroll buffer
	var image, <numCols, writeX = 0, history, colPixels;
	var <seconds = 15, <fps = 30;

	// --- look
	var <map, <mode = \scroll, <background, <>gridColor, <>textColor, <>font;
	var <showAxis = true, <showGrid = true, lut, lutSize = 512;

	// --- gui
	var <window, <userview, ownsWindow = false;
	var mouseX, mouseY, showCursor = false, cmdPeriodFunc;

	*new { arg parent, bounds, numCoeffs = 13, numBands = 40, startCoeff = 1,
		minFreq = 30, maxFreq = 18000, bufSize = 2048, inBus = 0, map,
		mode = \scroll, seconds = 15, fps = 30, server;

		^super.new.init(parent, bounds, numCoeffs, numBands, startCoeff,
			minFreq, maxFreq, bufSize, inBus, map, mode, seconds, fps, server);
	}

	init { arg parent, bounds, numCoeffsArg, numBandsArg, startCoeffArg,
		minFreqArg, maxFreqArg, bufSizeArg, inBusArg, mapArg, modeArg,
		secondsArg, fpsArg, serverArg;

		server = serverArg ? Server.default;
		numCoeffs = numCoeffsArg;
		numBands = numBandsArg;
		startCoeff = startCoeffArg;
		minFreq = minFreqArg;
		maxFreq = maxFreqArg;
		bufSize = bufSizeArg;
		inBus = inBusArg;
		mode = modeArg;
		seconds = secondsArg;
		fps = fpsArg;
		map = SpecColorMap(mapArg ? defaultMap);

		background = Color.grey(0.08);
		gridColor = Color.white.alpha_(0.14);
		textColor = Color.grey(0.7);
		font = Font(Font.defaultSansFace, 9);

		if(\FluidMFCC.asClass.isNil) {
			Error("MFCCView needs FluidMFCC (FluCoMa). Install the "
				"FluidCorpusManipulation package.").throw;
		};

		values = FloatArray.fill(numCoeffs, 0);
		this.prBuildLut;
		this.prBuildImage;
		this.prBuildGui(parent, bounds);
		^this
	}

	// =====================================================================
	// colour
	// =====================================================================

	prBuildLut {
		map.size_(lutSize);
		lut = map.asLut;
		if(map.isDiverging.not) {
			("MFCCView: '%' is a sequential map, but coefficients are signed. "
				"A diverging map reads better here - one of %.")
				.format(map.name ? "custom", SpecColorMap.divergingNames).inform;
		};
	}

	// signed value -> 0..1 with zero at the midpoint
	prNorm { arg v;
		var r = this.effectiveRange;
		^(((v / r) + 1) * 0.5).clip(0, 1)
	}

	effectiveRange { ^if(autoRange) { runningMax.max(1e-6) } { range } }

	// =====================================================================
	// buffers
	// =====================================================================

	prBuildImage {
		var old = image;
		numCols = (seconds * fps).asInteger.max(16);
		image = Image.color(numCols, numCoeffs, background);
		image.interpolation_(\smooth);   // few rows stretched tall, so smooth it
		history = FloatArray.fill(numCols * numCoeffs, 0);
		colPixels = Int32Array.newClear(numCoeffs);
		writeX = 0;
		if(old.notNil) { old.free };
	}

	clear {
		image.fill(background);
		history.fill(0);
		writeX = 0;
		this.refresh;
	}

	// =====================================================================
	// source
	// =====================================================================

	start {
		if(isRunning) { ^this };
		if(server.serverRunning.not) { "MFCCView: server is not running.".warn; ^this };
		if(server.hasShmInterface.not) {
			"MFCCView: no shared-memory interface - the server must be on "
			"localhost.".warn;
			^this
		};

		isRunning = true;
		cmdPeriodFunc = cmdPeriodFunc ?? { { this.prRestart } };
		CmdPeriod.add(cmdPeriodFunc);

		// shared with any other view asking for the same analysis
		feed = SpecAnalysisFeed.mfcc(server, inBus, numCoeffs, numBands,
			startCoeff, minFreq, maxFreq, bufSize);

		task = Task({
			var vals;
			0.2.wait;
			{
				vals = feed.read;
				if(vals.notNil) { this.prFrame(vals); this.refresh };
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
		feed !? { feed.release }; feed = nil;
	}

	// =====================================================================
	// per frame
	// =====================================================================

	prFrame { arg raw;
		var frameMax = 0, base, r, top = lutSize - 1;

		numCoeffs.do { |i|
			var v = raw[i];
			if(v.isNil or: { v.isNaN }) { v = 0 };
			values[i] = v;
			frameMax = max(frameMax, v.abs);
		};

		if(autoRange) {
			// jump up immediately, ease down, so the scale does not flap
			runningMax = if(frameMax > runningMax) {
				frameMax
			} {
				runningMax + ((frameMax - runningMax)
					* (autoDecay / fps.max(1)))
			};
			runningMax = runningMax.max(1e-6);
		};

		if(mode == \scroll) {
			r = this.effectiveRange;
			base = writeX * numCoeffs;
			numCoeffs.do { |i|
				// row 0 of the image is the top, so invert: highest
				// coefficient at the top, coefficient startCoeff at the bottom
				var row = numCoeffs - 1 - i;
				history[base + i] = values[i];
				colPixels[row] = lut[(this.prNorm(values[i]) * top).asInteger];
			};
			image.setPixels(colPixels, Rect(writeX, 0, 1, numCoeffs));
			writeX = (writeX + 1) % numCols;
		};
	}

	// =====================================================================
	// drawing
	// =====================================================================

	prPlotRect { arg b;
		var left = if(showAxis) { 30 } { 0 };
		var bottom = if(showAxis) { 15 } { 0 };
		^Rect(left, 4, b.width - left - 4, b.height - bottom - 4);
	}

	coeffIndex { arg i; ^(startCoeff + i) }

	prDraw { arg v;
		var r = this.prPlotRect(v.bounds);
		if(mode == \scroll) { this.prDrawScroll(r) } { this.prDrawBars(r) };
		if(showAxis) { this.prDrawAxis(r) };
		if(showCursor) { this.prDrawCursor(r) };
	}

	prDrawScroll { arg r;
		var sx = r.width / numCols, older, recent;
		if(image.isNil) { ^this };
		Pen.use {
			Pen.addRect(r); Pen.clip;
			recent = writeX;
			older = numCols - recent;
			if(older > 0) {
				Pen.drawImage(Rect(r.left, r.top, older * sx, r.height),
					image, Rect(recent, 0, older, numCoeffs));
			};
			if(recent > 0) {
				Pen.drawImage(
					Rect(r.left + (older * sx), r.top, recent * sx, r.height),
					image, Rect(0, 0, recent, numCoeffs));
			};
		};
		if(showGrid) {
			Pen.color = gridColor;
			Pen.width = 1;
			numCoeffs.do { |i|
				var y = r.top + ((i + 1) / numCoeffs * r.height);
				Pen.line(Point(r.left, y), Point(r.right, y));
			};
			Pen.stroke;
		};
	}

	prDrawBars { arg r;
		var w = r.width / numCoeffs, mid = r.top + (r.height / 2), half = r.height / 2;
		var rng = this.effectiveRange;

		Pen.use {
			Pen.addRect(r); Pen.clip;
			if(showGrid) {
				Pen.color = gridColor;
				Pen.width = 1;
				[-1, -0.5, 0.5, 1].do { |f|
					Pen.line(Point(r.left, mid - (f * half)),
						Point(r.right, mid - (f * half)));
				};
				Pen.stroke;
			};
			numCoeffs.do { |i|
				var v = values[i];
				var h = (v / rng).clip(-1, 1) * half;
				Pen.color = map.at(this.prNorm(v));
				Pen.addRect(Rect(r.left + (i * w), min(mid, mid - h),
					(w - 2).max(1), h.abs.max(1)));
				Pen.fill;
			};
			// the zero line, drawn over the bars
			Pen.color = Color.white.alpha_(0.45);
			Pen.width = 1;
			Pen.line(Point(r.left, mid), Point(r.right, mid));
			Pen.stroke;
		};
	}

	prDrawAxis { arg r;
		var step = (numCoeffs / 8).roundUp(1).asInteger.max(1);
		if(mode == \scroll) {
			numCoeffs.do { |i|
				if((i % step) == 0) {
					// coefficient i sits at row numCoeffs-1-i from the top
					var y = r.top + ((numCoeffs - 1 - i + 0.5) / numCoeffs * r.height);
					Pen.stringRightJustIn("c" ++ this.coeffIndex(i),
						Rect(0, y - 6, r.left - 4, 12), font, textColor);
				};
			};
			Pen.stringAtPoint("-" ++ seconds.round(0.1).asString ++ "s",
				Point(r.left + 2, r.bottom + 2), font, textColor);
			Pen.stringRightJustIn("now", Rect(r.right - 40, r.bottom + 2, 38, 12),
				font, textColor);
		} {
			var w = r.width / numCoeffs;
			numCoeffs.do { |i|
				if((i % step) == 0) {
					Pen.stringAtPoint("c" ++ this.coeffIndex(i),
						Point(r.left + (i * w) + 1, r.bottom + 2), font, textColor);
				};
			};
			Pen.stringRightJustIn("+" ++ this.effectiveRange.round(0.1),
				Rect(0, r.top - 1, r.left - 4, 12), font, textColor);
			Pen.stringRightJustIn("-" ++ this.effectiveRange.round(0.1),
				Rect(0, r.bottom - 11, r.left - 4, 12), font, textColor);
		};
		Pen.stringAtPoint(
			"% | range %% | %".format(mode,
				if(autoRange) { "auto " } { "" },
				this.effectiveRange.round(0.1),
				"c" ++ startCoeff ++ "-" ++ (startCoeff + numCoeffs - 1)),
			Point(r.left + 44, r.bottom + 2), font, textColor);
	}

	prDrawCursor { arg r;
		var i, v, label, col;
		if(mouseX.isNil or: { r.width <= 0 }) { ^this };
		if(mode == \scroll) {
			i = (((r.bottom - mouseY) / r.height) * numCoeffs).floor.asInteger
				.clip(0, numCoeffs - 1);
			col = (((mouseX - r.left) / r.width) * numCols).floor.asInteger
				.clip(0, numCols - 1);
			col = (writeX + col) % numCols;
			v = history[(col * numCoeffs) + i];
		} {
			i = (((mouseX - r.left) / r.width) * numCoeffs).floor.asInteger
				.clip(0, numCoeffs - 1);
			v = values[i];
		};
		Pen.use {
			Pen.addRect(r); Pen.clip;
			Pen.color = Color.white.alpha_(0.5);
			Pen.width = 1;
			Pen.line(Point(r.left, mouseY), Point(r.right, mouseY));
			Pen.line(Point(mouseX, r.top), Point(mouseX, r.bottom));
			Pen.stroke;
		};
		label = "c%  %".format(this.coeffIndex(i), v.round(0.01));
		Pen.color = Color.black.alpha_(0.75);
		Pen.addRect(Rect(r.left + 4, r.top + 2, 120, 14));
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
			window = Window("MFCCView", bounds ?? { Rect(180, 260, 780, 320) },
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
				{ ch == $m } { this.mode_(if(mode == \scroll) { \bars } { \scroll }) }
				{ ch == $a } { this.autoRange_(autoRange.not) }
				{ ch == $g } { this.showGrid_(showGrid.not) }
				{ ch == $c } { this.clear };
			});

		if(ownsWindow) { window.onClose_({ this.stop }).front };
	}

	refresh { if(userview.notNil and: { userview.notClosed }) { userview.refresh } }
	close { this.stop; if(ownsWindow and: { window.notNil }) { window.close } }

	// =====================================================================
	// configuration
	// =====================================================================

	map_ { arg m; map = SpecColorMap(m); this.prBuildLut; this.refresh }
	mode_ { arg m; mode = m; this.refresh }
	background_ { arg c; background = c; userview !? { userview.background_(c) }; this.refresh }
	showGrid_ { arg bool; showGrid = bool; this.refresh }
	showAxis_ { arg bool; showAxis = bool; this.refresh }

	range_ { arg r; range = r; autoRange = false; this.refresh }
	autoRange_ { arg bool; autoRange = bool; this.refresh }
	autoDecay_ { arg x; autoDecay = x }

	fps_ { arg f; fps = f.clip(1, 120); this.prBuildImage; this.refresh }
	seconds_ { arg sec; seconds = sec.max(0.5); this.prBuildImage; this.refresh }

	// changes which analysis is wanted, so the feed has to be swapped
	inBus_ { arg b; this.prReshape({ inBus = b }) }

	// these change the shape of the analysis, so the synth has to restart
	numCoeffs_ { arg n; this.prReshape({ numCoeffs = n.clip(2, 128) }) }
	numBands_ { arg n; this.prReshape({ numBands = n.clip(2, 256) }) }
	startCoeff_ { arg n; this.prReshape({ startCoeff = n.max(0) }) }

	prReshape { arg changes;
		var wasRunning = isRunning;
		if(wasRunning) { this.stop };
		changes.value;
		values = FloatArray.fill(numCoeffs, 0);
		runningMax = 1;
		this.prBuildImage;
		if(wasRunning) { this.start };
		this.refresh;
	}

	freqRange_ { arg lo, hi;
		this.prReshape({
			minFreq = lo.max(1);
			maxFreq = hi.min((server.sampleRate ? 44100) / 2);
		});
	}

	// --- inspection ---

	coeffs { ^values.copy }
	coeffIndices { ^(startCoeff .. (startCoeff + numCoeffs - 1)) }

	info {
		"MFCCView\n"
		"  coeffs : % of them, c%..c% (from % mel bands, % to % Hz, window %)\n"
		"  scale  : % range %\n"
		"  view   : mode %, % s at % fps, map %\n".postf(
			numCoeffs, startCoeff, startCoeff + numCoeffs - 1, numBands,
			minFreq.round(0.1), maxFreq.round(0.1), bufSize,
			if(autoRange) { "auto," } { "fixed," }, this.effectiveRange.round(0.01),
			mode, seconds, fps, map.name ? "custom");
	}
}
