// StereoPhaseView - stereo field and phase analysis.
//
// Four renderings of one cheap control bus, switch live with mode_ (or "m"):
//
//   \position  Polar stereo field, after the Waves PAZ Position panel: a half
//              circle from hard left through centre to hard right, showing how
//              much energy arrives from each DIRECTION. Every band's energy is
//              binned by the direction its L/R balance implies, giving a
//              continuous lobe - a narrow upright spike for mono, a broad fan
//              for a wide mix, separate humps for hard-panned material.
//
//              Out-of-phase energy appears as a second lobe BELOW the horizon,
//              in red. It carries only the anti-correlated share of each band
//              (energy * max(0, -correlation)), so it stays empty for in-phase
//              material AND for merely-uncorrelated-wide material: anything
//              below the line is genuinely phase-cancelling. Correlation meter
//              under that.
//
//              showBands_(true) overlays the per-band spokes, coloured by
//              frequency, when you want to know which material sits where
//              rather than just how much. (Spokes alone were the first attempt
//              at this view and read as an unintelligible star - the direction
//              histogram is what makes spread legible.)
//   \gonio     Classic Lissajous vectorscope with phosphor persistence.
//              Mono reads as a vertical line, out-of-phase as horizontal.
//   \bands     Per-band correlation as bars, diverging colour, so a band that
//              has gone out of phase is obvious.
//   \history   Overall correlation scrolling over time.
//
// All of it except \gonio comes from one SynthDef: per band, RunningSum of
// L*L, R*R and L*R. Those three sums give both the band levels and the
// correlation, so nothing is computed twice. The result goes to a control bus
// read through shared memory - no OSC per frame. \gonio additionally needs
// real samples, so it records to a buffer and pulls it over OSC, which is why
// its recorder only runs while that mode is showing.
//
// Correlation convention: +1 both channels identical, 0 uncorrelated (wide),
// -1 one is the other inverted (mono-incompatible).
//
// On `integration`. Correlation is estimated over a sliding window, so the
// reading has variance, and how much depends on the material: low-frequency
// energy means few independent cycles per window. Measured here, two
// UNCORRELATED sources (true correlation 0) read as:
//
//     source        window    mean      std dev   worst
//     white noise   0.05 s     0.002     0.019     0.046
//     white noise   0.20 s    -0.002     0.012     0.033
//     white noise   0.50 s     0.000     0.008     0.015
//     pink noise    0.05 s    -0.143     0.319     0.724   <- unusable
//     pink noise    0.20 s     0.015     0.258     0.648
//     pink noise    0.50 s     0.188     0.154     0.499
//
// Music is pink-ish, so a 50 ms window makes the meter flail by +/-0.3 on
// nothing at all. Hence the 0.3 s default, which is also roughly where
// hardware correlation meters sit. Shorten it to catch brief phase events,
// lengthen it for a reading you can actually read. Fully correlated and
// fully inverted material pins at exactly +1 and -1 at any window length.
//
//   p = StereoPhaseView.new;  p.start;
//
//   p.mode_(\gonio);  p.mode_(\position);
//   p.numBands_(24);
//   p.showBands_(true);          // per-band spokes over the lobe
//   p.lobeRange_(36);            // dB of direction range the lobe shows
//   p.dirBins_(61);              // angular resolution of the lobe
//   p.integration_(0.05);        // averaging window, seconds (see note below)
//   p.freqRange_(40, 16000);
//   p.dbRange_(-60, 0);
//   p.persistence_(0.82);        // \gonio phosphor trail, 0 to <1
//   p.correlation;  p.bandCorrelations;  p.balance;
//   p.stop;  p.close;

StereoPhaseView {

	classvar <>defaultMap = \viridis;
	classvar <>defaultCorrMap = \coolwarm;

	// --- source
	var <numBands, <minFreq, <maxFreq, <bandQ = 1.0, <bandRq = 0.5;
	var <integration = 0.3;
	var <inBus, <server, corrBus, corrSynth, scopeBuf, scopeSynth, task;
	var <isRunning = false, <scopeFrames = 1024;

	// --- data
	var <correlation = 0, <rmsL = 0, <rmsR = 0;
	var <dirBins = 61, lobeUp, lobeDown, <autoScale = true, scaleMax = 1e-9;
	var <lobeRange = 36;   // dB below the loudest direction that still shows
	var <showBands = false;
	var bandL, bandR, bandCorr, bandPeak, bandFreqs;
	var scopePoints, scopeInFlight = false, scopeOsc, lastScopeReq = 0;

	// --- scrolling correlation history
	var corrHistory, histPos = 0, <historySeconds = 20;

	// --- look
	var <mode = \position, <map, <corrMap, <dbFloor = -60, <dbCeil = 0;
	var <persistence = 0.82, <peakHold = 1.2, peakAges;
	var <background, <>gridColor, <>textColor, <>font, <scopeColor;
	var <fps = 30, <showGrid = true, <showAxis = true;
	var scopeImage, <scopeSize = 420;

	// --- gui
	var <window, <userview, ownsWindow = false;
	var mouseX, mouseY, showCursor = false, cmdPeriodFunc;

	*new { arg parent, bounds, numBands = 16, minFreq = 40, maxFreq = 16000,
		inBus = 0, mode = \position, map, dbFloor = -60, dbCeil = 0,
		fps = 30, integration = 0.3, server;

		^super.new.init(parent, bounds, numBands, minFreq, maxFreq, inBus,
			mode, map, dbFloor, dbCeil, fps, integration, server);
	}

	init { arg parent, bounds, numBandsArg, minFreqArg, maxFreqArg, inBusArg,
		modeArg, mapArg, dbFloorArg, dbCeilArg, fpsArg, integrationArg, serverArg;

		server = serverArg ? Server.default;
		numBands = numBandsArg;
		minFreq = minFreqArg;
		maxFreq = maxFreqArg;
		inBus = inBusArg;
		mode = modeArg;
		dbFloor = dbFloorArg;
		dbCeil = dbCeilArg;
		fps = fpsArg;
		integration = integrationArg;
		map = SpecColorMap(mapArg ? defaultMap);
		corrMap = SpecColorMap(defaultCorrMap);

		background = Color.grey(0.07);
		gridColor = Color.white.alpha_(0.16);
		textColor = Color.grey(0.72);
		scopeColor = Color(0.45, 1.0, 0.6);
		font = Font(Font.defaultSansFace, 9);

		this.prBuildBands;
		this.prBuildHistory;
		this.prBuildGui(parent, bounds);
		^this
	}

	// =====================================================================
	// layout of the analysis
	// =====================================================================

	prBuildBands {
		var ratio;
		bandFreqs = numBands.collect { |i|
			minFreq * ((maxFreq / minFreq) ** (i / (numBands - 1).max(1)))
		};
		// BPF's third argument is rq, the RECIPROCAL of Q: bandwidth over
		// centre frequency. A fixed 0.7 is far wider than the spacing between
		// bands once you have more than a handful of them, and wide bands
		// smear direction - a band that overlaps both a hard-left and a
		// hard-right source reports the average, i.e. centre, inventing energy
		// in directions nothing is coming from. So derive it from the spacing
		// and let bandQ act as a width multiplier around that.
		ratio = (maxFreq / minFreq) ** (1 / (numBands - 1).max(1));
		bandRq = ((ratio - ratio.reciprocal) * bandQ).clip(0.01, 2);
		bandL    = FloatArray.fill(numBands, 0);
		bandR    = FloatArray.fill(numBands, 0);
		bandCorr = FloatArray.fill(numBands, 0);
		bandPeak = FloatArray.fill(numBands, 0);
		peakAges = FloatArray.fill(numBands, 0);
		lobeUp   = FloatArray.fill(dirBins, 0);
		lobeDown = FloatArray.fill(dirBins, 0);
		scaleMax = 1e-9;
	}

	prBuildHistory {
		corrHistory = FloatArray.fill((historySeconds * fps).asInteger.max(16), 0);
		histPos = 0;
	}

	prIntegrationSamples {
		^(integration * (server.sampleRate ? 44100)).asInteger.clip(64, 65536)
	}

	// =====================================================================
	// source
	// =====================================================================

	prSendDefs {
		var nSamp = this.prIntegrationSamples;

		// One pass of RunningSums per band gives levels AND correlation.
		SynthDef(\stereoPhase_corr, { |inbus = 0, out = 0, q = 0.7|
			var sig = InFeedback.ar(inbus, 2);
			var l = sig[0], r = sig[1];
			var eps = 1e-9;
			var sLL = RunningSum.ar(l * l, nSamp);
			var sRR = RunningSum.ar(r * r, nSamp);
			var sLR = RunningSum.ar(l * r, nSamp);
			var overall = sLR / (sqrt(sLL * sRR) + eps);
			var perBand = bandFreqs.collect { |f|
				// Cascaded, i.e. 4th order. A single BPF's skirts are shallow
				// enough that a band sitting between a hard-left source and a
				// hard-right one passes a good deal of both and reports the
				// average - centre - putting energy on the display in a
				// direction nothing is actually coming from. Two in series
				// roughly doubles the rejection for one extra cheap filter.
				// rq is widened to keep about the same -3 dB width.
				var bl = BPF.ar(BPF.ar(l, f, q * 1.4), f, q * 1.4);
				var br = BPF.ar(BPF.ar(r, f, q * 1.4), f, q * 1.4);
				var bLL = RunningSum.ar(bl * bl, nSamp);
				var bRR = RunningSum.ar(br * br, nSamp);
				var bLR = RunningSum.ar(bl * br, nSamp);
				[
					sqrt(bLL / nSamp),                       // level L
					sqrt(bRR / nSamp),                       // level R
					bLR / (sqrt(bLL * bRR) + eps)            // correlation
				]
			}.flatten;

			Out.kr(out, A2K.kr(
				[overall, sqrt(sLL / nSamp), sqrt(sRR / nSamp)] ++ perBand));
		}).send(server);

		SynthDef(\stereoPhase_scope, { |inbus = 0, buffer = 0|
			RecordBuf.ar(InFeedback.ar(inbus, 2), buffer, loop: 1);
		}).send(server);
	}

	start {
		if(isRunning) { ^this };
		if(server.serverRunning.not) { "StereoPhaseView: server not running.".warn; ^this };
		if(server.hasShmInterface.not) {
			"StereoPhaseView: no shared-memory interface - server must be on "
			"localhost.".warn;
			^this
		};

		isRunning = true;
		this.prSendDefs;
		cmdPeriodFunc = cmdPeriodFunc ?? { { this.prRestart } };
		CmdPeriod.add(cmdPeriodFunc);

		corrBus = Bus.control(server, 3 + (numBands * 3));
		scopeBuf = Buffer.alloc(server, scopeFrames, 2);
		scopePoints = FloatArray.newClear(scopeFrames * 2);

		scopeOsc = OSCFunc({ |msg|
			if(scopeBuf.notNil and: { msg[1] == scopeBuf.bufnum }) {
				scopePoints = scopePoints.overWrite(
					FloatArray.newFrom(msg.copyToEnd(4)), msg[2]);
				scopeInFlight = false;
			};
		}, '/b_setn', server.addr);

		task = Task({
			server.sync;
			corrSynth = Synth(\stereoPhase_corr,
				[\inbus, inBus, \out, corrBus, \q, bandRq], server);
			0.2.wait;
			{
				this.prFrame(corrBus.getnSynchronous(3 + (numBands * 3)));
				if(mode == \gonio) {
					this.prRequestScope;
					this.prUpdateScopeImage;
				};
				this.refresh;
				fps.reciprocal.wait;
			}.loop;
		}).play(AppClock);

		if(mode == \gonio) { this.prStartScope };
	}

	prRestart {
		if(isRunning) {
			this.stop;
			AppClock.sched(0.6, { this.start; nil });
		};
	}

	// the sample recorder only runs while the goniometer is actually showing
	prStartScope {
		if(scopeSynth.isNil and: { isRunning }) {
			scopeSynth = Synth.after(corrSynth, \stereoPhase_scope,
				[\inbus, inBus, \buffer, scopeBuf]);
		};
	}

	prStopScope {
		try { scopeSynth.free }; scopeSynth = nil;
		scopeInFlight = false;
		scopeImage !? { scopeImage.free; scopeImage = nil };
	}

	prRequestScope {
		var n = scopeFrames * 2;
		if(scopeBuf.isNil) { ^this };
		if(scopeInFlight and: { (Main.elapsedTime - lastScopeReq) > 0.5 }) {
			scopeInFlight = false;
		};
		if(scopeInFlight.not) {
			var pos = 0, chunk = 1632, msgs = Array.new(4);
			while { pos < n } {
				var take = min(chunk, n - pos);
				msgs = msgs.add(scopeBuf.getnMsg(pos, take));
				pos = pos + take;
			};
			scopeInFlight = true;
			lastScopeReq = Main.elapsedTime;
			server.listSendBundle(nil, msgs);
		};
	}

	stop {
		isRunning = false;
		task.stop; task = nil;
		if(cmdPeriodFunc.notNil) { CmdPeriod.remove(cmdPeriodFunc) };
		this.prStopScope;
		try { scopeOsc.free };  scopeOsc = nil;
		try { corrSynth.free }; corrSynth = nil;
		try { corrBus.free };   corrBus = nil;
		try { scopeBuf.free };  scopeBuf = nil;
	}

	// =====================================================================
	// per frame
	// =====================================================================

	prFrame { arg raw;
		var dt = fps.reciprocal;
		correlation = raw[0];
		rmsL = raw[1];
		rmsR = raw[2];
		if(correlation.isNaN) { correlation = 0 };

		numBands.do { |i|
			var o = 3 + (i * 3);
			var lev;
			bandL[i] = raw[o];
			bandR[i] = raw[o + 1];
			bandCorr[i] = if(raw[o + 2].isNaN) { 0 } { raw[o + 2] };
			lev = this.bandRadius(i);
			if(lev >= bandPeak[i]) {
				bandPeak[i] = lev;
				peakAges[i] = 0;
			} {
				peakAges[i] = peakAges[i] + dt;
				if(peakAges[i] > peakHold) {
					bandPeak[i] = (bandPeak[i] - (dt * 0.8)).max(0);
				};
			};
		};

		this.prBuildLobe(dt);

		corrHistory[histPos] = correlation;
		histPos = (histPos + 1) % corrHistory.size;
	}

	// --- derived values -------------------------------------------------

	// -1 hard left, 0 centre, +1 hard right
	bandPosition { arg i;
		var l = bandL[i], r = bandR[i];
		if((l + r) < 1e-7) { ^0 };
		^(((r.atan2(l) / (pi / 4)) - 1).clip(-1, 1))
	}

	// 0..1 from the dB window
	bandRadius { arg i;
		var mag = ((bandL[i].squared + bandR[i].squared) / 2).sqrt;
		var db = (mag.max(1e-9).log10 * 20);
		^((db - dbFloor) / (dbCeil - dbFloor).max(1e-9)).clip(0, 1)
	}

	balance {
		var t = rmsL + rmsR;
		^if(t < 1e-7) { 0 } { (rmsR - rmsL) / t }
	}

	bandCorrelations { ^bandCorr.copy }
	bandLevels { ^[bandL.copy, bandR.copy] }
	bandFreqs { ^bandFreqs.copy }

	// =====================================================================
	// drawing
	// =====================================================================

	prPlotRect { arg b;
		var bottom = if(mode == \position) { 34 } { if(showAxis) { 16 } { 2 } };
		^Rect(4, 4, b.width - 8, b.height - bottom - 4)
	}

	prDraw { arg v;
		var r = this.prPlotRect(v.bounds);
		switch(mode,
			\position, { this.prDrawPosition(r); this.prDrawCorrBar(v.bounds, r) },
			\gonio,    { this.prDrawGonio(r) },
			\bands,    { this.prDrawBands(r) },
			\history,  { this.prDrawHistory(r) },
			{ this.prDrawPosition(r) }
		);
		if(showCursor) { this.prDrawReadout(r) };
	}

	// --- PAZ-style polar stereo field -----------------------------------

	// Half circle from hard left, up through centre, to hard right, sampled on
	// exactly the same pos -> angle mapping the spokes and the band points
	// use, so the web always lines up with what it is measuring.
	//
	// Pen.addArc is deliberately not used. It connects the current path
	// position to the arc's start rather than beginning a new subpath, so the
	// rings got joined to the last spoke by stray chords; and its angle
	// convention (it negates both arguments, then Qt measures from 3 o'clock
	// on a y-down canvas) does not agree with the sin/cos mapping used for the
	// data. Together those drew a lopsided web - measured 726 pixels of grid
	// on the left against 1918 on the right.
	prHalfArc { arg cx, cy, radius, steps = 49;
		steps.do { |i|
			var pos = (i / (steps - 1) * 2) - 1;
			var a = pos * (pi / 2);
			var pt = Point(cx + (sin(a) * radius), cy - (cos(a) * radius));
			if(i == 0) { Pen.moveTo(pt) } { Pen.lineTo(pt) };
		};
	}


	// Energy against DIRECTION, which is what makes a stereo field legible.
	// One spoke per frequency band draws a star that says almost nothing about
	// spread; binning every band's energy by the direction it arrives from
	// gives a lobe you can actually read - narrow and upright for mono, a wide
	// fan for a broad mix, two humps for hard-panned material.
	//
	// The lower lobe is the anti-correlated portion only (energy * max(0, -c)),
	// so it stays empty for in-phase AND for merely-uncorrelated-wide content,
	// and only grows when something is genuinely phase-cancelling. Anything
	// below the horizon is a real problem; that is the whole point of it.
	prBuildLobe { arg dt;
		var frameMax = 0;

		dirBins.do { |k| lobeUp[k] = 0; lobeDown[k] = 0 };

		numBands.do { |i|
			var l = bandL[i], r = bandR[i];
			var energy = ((l * l) + (r * r)) / 2;     // power adds linearly
			var c = bandCorr[i].clip(-1, 1);
			var fk, k0, k1, frac, down;
			if(energy > 1e-12) {
				// split across the two nearest bins, so moving a source does
				// not make the lobe jump a whole bin at a time
				fk = (((this.bandPosition(i) + 1) / 2) * (dirBins - 1))
					.clip(0, dirBins - 1);
				k0 = fk.floor.asInteger;
				k1 = (k0 + 1).min(dirBins - 1);
				frac = fk - k0;
				down = energy * max(0, c.neg);
				lobeUp[k0] = lobeUp[k0] + (energy * (1 - frac));
				lobeUp[k1] = lobeUp[k1] + (energy * frac);
				lobeDown[k0] = lobeDown[k0] + (down * (1 - frac));
				lobeDown[k1] = lobeDown[k1] + (down * frac);
			};
		};

		// a couple of smoothing passes, so a handful of bands still reads as
		// a continuous shape rather than isolated spikes
		2.do {
			lobeUp = this.prSmooth(lobeUp);
			lobeDown = this.prSmooth(lobeDown);
		};

		dirBins.do { |k| frameMax = max(frameMax, lobeUp[k]) };
		if(autoScale) {
			scaleMax = if(frameMax > scaleMax) {
				frameMax
			} {
				scaleMax + ((frameMax - scaleMax) * (0.5 * dt))
			};
			scaleMax = scaleMax.max(1e-12);
		};
	}

	prSmooth { arg arr;
		var out = FloatArray.newClear(arr.size);
		arr.size.do { |i|
			out[i] = (arr[(i - 1).clip(0, arr.size - 1)]
				+ (arr[i] * 2)
				+ arr[(i + 1).clip(0, arr.size - 1)]) / 4;
		};
		^out
	}

	// bin index -> unit radius, via dB so quiet directions still register
	// Radius from energy, in dB relative to the loudest direction.
	//
	// This gets its own range rather than reusing dbFloor/dbCeil, which exist
	// for the per-band spokes. Over a 60 dB window a direction carrying a
	// thousandth of the energy still reaches half radius, so the lobe looks
	// full no matter what is playing and stops discriminating. 36 dB keeps
	// quiet directions visible without letting them dominate the shape.
	prLobeRadius { arg energy;
		var ref = if(autoScale) { scaleMax } { 1.0 };
		var db = (energy / ref).max(1e-12).log10 * 10;
		^((db + lobeRange) / lobeRange).clip(0, 1)
	}

	// direction bin -> angle from straight up, same mapping the spokes use
	prBinAngle { arg k;
		^(((k / (dirBins - 1)) * 2) - 1) * (pi / 2)
	}

	prDrawPosition { arg r;
		// centre sits high enough to leave room for the out-of-phase lobe below
		var cx = r.center.x, cy = r.top + (r.height * 0.72);
		var rad = min(r.width / 2, r.height * 0.72) - 14;

		Pen.use {
			Pen.addRect(r); Pen.clip;

			if(showGrid) {
				Pen.color = gridColor;
				Pen.width = 1;
				[-1, -0.5, 0, 0.5, 1].do { |p|
					var a = p * (pi / 2);
					Pen.line(Point(cx, cy),
						Point(cx + (sin(a) * rad), cy - (cos(a) * rad)));
				};
				Pen.stroke;
				[0.25, 0.5, 0.75, 1].do { |f|
					this.prHalfArc(cx, cy, rad * f);
					Pen.stroke;
				};
				// the horizon: anything under this line is out of phase
				Pen.color = gridColor;
				Pen.line(Point(cx - rad, cy), Point(cx + rad, cy));
				Pen.stroke;
			};

			// --- out-of-phase lobe, mirrored below the horizon ---
			this.prFillLobe(cx, cy, rad, lobeDown, -1,
				Color(1, 0.3, 0.3, 0.55), Color(1, 0.45, 0.45));

			// --- the stereo field itself ---
			this.prFillLobe(cx, cy, rad, lobeUp, 1,
				map.at(0.65).alpha_(0.5), map.at(0.95));

			// --- optional per-band detail on top ---
			if(showBands) {
				numBands.do { |i|
					var a = this.bandPosition(i) * (pi / 2);
					var rr = this.bandRadius(i);
					if(rr > 0.005) {
						Pen.color = map.at(i / (numBands - 1).max(1)).alpha_(0.9);
						Pen.width = 2;
						Pen.line(Point(cx, cy),
							Point(cx + (sin(a) * rad * rr), cy - (cos(a) * rad * rr)));
						Pen.stroke;
					};
				};
			};
		};

		if(showAxis) {
			Pen.stringAtPoint("L", Point(cx - rad - 2, cy - 13), font, textColor);
			Pen.stringAtPoint("R", Point(cx + rad - 4, cy - 13), font, textColor);
			Pen.stringCenteredIn("C", Rect(cx - 8, r.top, 16, 12), font, textColor);
			// any stereo material throws off a little negative correlation in
			// some band; only say so when it is an actual share of the energy
			if(lobeDown.maxItem > (lobeUp.maxItem * 0.02)) {
				Pen.stringAtPoint("out of phase",
					Point(cx + 6, cy + 4), font, Color(1, 0.45, 0.45));
			};
		};
	}

	// dir points up (+1) for the field, down (-1) for the anti-correlated part
	prFillLobe { arg cx, cy, rad, bins, dir, fillColor, lineColor;
		var pts, any = false;
		pts = dirBins.collect { |k|
			var rr = this.prLobeRadius(bins[k]);
			var a = this.prBinAngle(k);
			if(rr > 0.001) { any = true };
			Point(cx + (sin(a) * rad * rr), cy - (dir * cos(a) * rad * rr))
		};
		if(any.not) { ^this };

		Pen.color = fillColor;
		Pen.moveTo(Point(cx, cy));
		pts.do { |pt| Pen.lineTo(pt) };
		Pen.lineTo(Point(cx, cy));
		Pen.fill;

		Pen.color = lineColor;
		Pen.width = 1.5;
		Pen.moveTo(pts[0]);
		pts.drop(1).do { |pt| Pen.lineTo(pt) };
		Pen.stroke;
	}

	// --- correlation meter, under the position display ------------------

	prDrawCorrBar { arg b, r;
		var bar = Rect(r.left + 30, b.height - 26, r.width - 60, 10);
		var cx = bar.center.x, half = bar.width / 2;
		var x = cx + (correlation.clip(-1, 1) * half);

		Pen.color = Color.grey(0.2);
		Pen.addRect(bar); Pen.fill;

		Pen.color = gridColor;
		Pen.width = 1;
		[-1, -0.5, 0, 0.5, 1].do { |f|
			var gx = cx + (f * half);
			Pen.line(Point(gx, bar.top), Point(gx, bar.bottom));
		};
		Pen.stroke;

		// fill from centre out to the current value
		Pen.color = corrMap.at((correlation.clip(-1, 1) + 1) / 2);
		Pen.addRect(Rect(min(cx, x), bar.top, (x - cx).abs.max(1), bar.height));
		Pen.fill;

		Pen.stringRightJustIn("-1", Rect(bar.left - 24, bar.top - 1, 20, 12),
			font, textColor);
		Pen.stringAtPoint("+1", Point(bar.right + 4, bar.top - 1), font, textColor);
		Pen.stringCenteredIn(
			"corr % | bal %".format(correlation.round(0.01), this.balance.round(0.01)),
			Rect(bar.left, bar.bottom + 1, bar.width, 12), font, textColor);
	}

	// --- Lissajous ------------------------------------------------------

	// Compositing the phosphor trail has to happen OUTSIDE the view's
	// drawFunc. Image:-draw installs a painter, and Qt will not nest one
	// inside the painter already active for the widget - doing it in drawFunc
	// fails with _QImage_SetPainter. So the task loop calls this, and the
	// draw pass below only blits the result.
	prUpdateScopeImage {
		var half = scopeSize / 2, n = scopeFrames;
		if(scopePoints.isNil) { ^this };
		if(scopeImage.isNil) {
			scopeImage = Image.color(scopeSize, scopeSize, background);
			scopeImage.interpolation_(\smooth);
		};
		scopeImage.draw({
			// fade what is already there, then add this frame's dots
			Pen.color = background.alpha_(1 - persistence);
			Pen.addRect(Rect(0, 0, scopeSize, scopeSize));
			Pen.fill;

			Pen.color = scopeColor.alpha_(0.5);
			n.do { |i|
				var l = scopePoints[i * 2], rr = scopePoints[(i * 2) + 1];
				// mid/side rotation: mono is vertical, out-of-phase horizontal
				var mx = (rr - l) * 0.7071, my = (l + rr) * 0.7071;
				var px = half + (mx * half), py = half - (my * half);
				Pen.addRect(Rect(px, py, 1.6, 1.6));
			};
			Pen.fill;
		});
	}

	prDrawGonio { arg r;
		var side = min(r.width, r.height);
		var dest = Rect(r.center.x - (side / 2), r.center.y - (side / 2), side, side);

		if(scopeImage.notNil) {
			Pen.drawImage(dest, scopeImage, Rect(0, 0, scopeSize, scopeSize));
		};

		if(showGrid) {
			Pen.color = gridColor;
			Pen.width = 1;
			Pen.line(Point(dest.center.x, dest.top), Point(dest.center.x, dest.bottom));
			Pen.line(Point(dest.left, dest.center.y), Point(dest.right, dest.center.y));
			Pen.stroke;
			// addOval starts its own subpath; addArc would chord back to the
			// end of the last line above
			Pen.addOval(dest);
			Pen.stroke;
		};
		if(showAxis) {
			Pen.stringAtPoint("M", Point(dest.center.x + 3, dest.top + 1), font, textColor);
			Pen.stringAtPoint("S", Point(dest.right - 12, dest.center.y + 2), font, textColor);
			Pen.stringAtPoint("corr % | bal %".format(
				correlation.round(0.01), this.balance.round(0.01)),
				Point(r.left + 2, r.bottom - 12), font, textColor);
		};
	}

	// --- per-band correlation bars --------------------------------------

	prDrawBands { arg r;
		var w = r.width / numBands, mid = r.center.y, half = r.height / 2;
		Pen.use {
			Pen.addRect(r); Pen.clip;
			if(showGrid) {
				Pen.color = gridColor;
				Pen.width = 1;
				[-1, -0.5, 0, 0.5, 1].do { |f|
					Pen.line(Point(r.left, mid - (f * half)),
						Point(r.right, mid - (f * half)));
				};
				Pen.stroke;
			};
			numBands.do { |i|
				var c = bandCorr[i].clip(-1, 1);
				var h = c * half;
				Pen.color = corrMap.at((c + 1) / 2);
				Pen.addRect(Rect(r.left + (i * w), min(mid, mid - h),
					(w - 2).max(1), h.abs.max(1)));
				Pen.fill;
			};
			Pen.color = Color.white.alpha_(0.45);
			Pen.width = 1;
			Pen.line(Point(r.left, mid), Point(r.right, mid));
			Pen.stroke;
		};
		if(showAxis) {
			var step = (numBands / 6).roundUp(1).asInteger.max(1);
			numBands.do { |i|
				if((i % step) == 0) {
					Pen.stringAtPoint(this.prFreqLabel(bandFreqs[i]),
						Point(r.left + (i * w) + 1, r.bottom + 2), font, textColor);
				};
			};
			Pen.stringRightJustIn("+1", Rect(r.left - 22, r.top - 1, 20, 12), font, textColor);
			Pen.stringRightJustIn("-1", Rect(r.left - 22, r.bottom - 11, 20, 12), font, textColor);
		};
	}

	// --- correlation over time ------------------------------------------

	prDrawHistory { arg r;
		var n = corrHistory.size, sx = r.width / n, mid = r.center.y, half = r.height / 2;
		Pen.use {
			Pen.addRect(r); Pen.clip;
			if(showGrid) {
				Pen.color = gridColor;
				Pen.width = 1;
				[-1, -0.5, 0, 0.5, 1].do { |f|
					Pen.line(Point(r.left, mid - (f * half)),
						Point(r.right, mid - (f * half)));
				};
				Pen.stroke;
			};
			Pen.width = 1.5;
			Pen.color = scopeColor;
			n.do { |k|
				var i = (histPos + k) % n;
				var p = Point(r.left + (k * sx), mid - (corrHistory[i].clip(-1, 1) * half));
				if(k == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
		};
		if(showAxis) {
			Pen.stringAtPoint("-" ++ historySeconds.asString ++ "s",
				Point(r.left + 2, r.bottom + 2), font, textColor);
			Pen.stringRightJustIn("now", Rect(r.right - 40, r.bottom + 2, 38, 12),
				font, textColor);
			Pen.stringRightJustIn("+1", Rect(r.left - 22, r.top - 1, 20, 12), font, textColor);
			Pen.stringRightJustIn("-1", Rect(r.left - 22, r.bottom - 11, 20, 12), font, textColor);
		};
	}

	prFreqLabel { arg f;
		^if(f >= 1000) { (f / 1000).round(0.1).asString ++ "k" } { f.round(1).asInteger.asString }
	}

	prDrawReadout { arg r;
		var label, i;
		if(mouseX.isNil) { ^this };
		label = switch(mode,
			\bands, {
				i = (((mouseX - r.left) / r.width) * numBands).floor.asInteger
					.clip(0, numBands - 1);
				"% Hz  corr %".format(bandFreqs[i].round(1), bandCorr[i].round(0.01))
			},
			\position, {
				i = (((mouseX - r.left) / r.width) * numBands).floor.asInteger
					.clip(0, numBands - 1);
				"% Hz  pos %".format(bandFreqs[i].round(1),
					this.bandPosition(i).round(0.01))
			},
			{ "corr %  bal %".format(correlation.round(0.01), this.balance.round(0.01)) }
		);
		Pen.color = Color.black.alpha_(0.75);
		Pen.addRect(Rect(r.left + 4, r.top + 2, 150, 14));
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
			window = Window("StereoPhaseView",
				bounds ?? { Rect(200, 200, 460, 480) }, scroll: false);
			b = window.view.bounds;
		} {
			window = parent;
			b = bounds ?? { parent.asView.bounds };
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
				{ ch == $m } { this.prCycleMode }
				{ ch == $g } { this.showGrid_(showGrid.not) }
				{ ch == $b } { this.showBands_(showBands.not) };
			});

		if(ownsWindow) { window.onClose_({ this.stop }).front };
	}

	prCycleMode {
		var order = [\position, \gonio, \bands, \history];
		this.mode_(order.wrapAt(order.indexOf(mode) + 1));
	}

	refresh { if(userview.notNil and: { userview.notClosed }) { userview.refresh } }
	close { this.stop; if(ownsWindow and: { window.notNil }) { window.close } }

	// =====================================================================
	// configuration
	// =====================================================================

	mode_ { arg m;
		mode = m;
		if(isRunning) {
			if(m == \gonio) { this.prStartScope } { this.prStopScope };
		};
		this.refresh;
	}

	map_ { arg m; map = SpecColorMap(m); this.refresh }
	corrMap_ { arg m; corrMap = SpecColorMap(m); this.refresh }
	scopeColor_ { arg c; scopeColor = c; this.refresh }
	background_ { arg c;
		background = c;
		userview !? { userview.background_(c) };
		scopeImage !? { scopeImage.free; scopeImage = nil };
		this.refresh;
	}

	dbFloor_ { arg db; dbFloor = db; this.refresh }
	dbCeil_ { arg db; dbCeil = db; this.refresh }
	dbRange_ { arg lo, hi = 0; dbFloor = lo; dbCeil = hi; this.refresh }

	persistence_ { arg p; persistence = p.clip(0, 0.99); this.refresh }
	peakHold_ { arg sec; peakHold = sec }
	showGrid_ { arg bool; showGrid = bool; this.refresh }
	showBands_ { arg bool; showBands = bool; this.refresh }
	autoScale_ { arg bool; autoScale = bool; this.refresh }
	lobeRange_ { arg db; lobeRange = db.clip(6, 120); this.refresh }
	dirBins_ { arg n;
		dirBins = n.clip(9, 361);
		lobeUp = FloatArray.fill(dirBins, 0);
		lobeDown = FloatArray.fill(dirBins, 0);
		this.refresh;
	}
	showAxis_ { arg bool; showAxis = bool; this.refresh }

	fps_ { arg f; fps = f.clip(1, 120); this.prBuildHistory; this.refresh }
	historySeconds_ { arg sec; historySeconds = sec.max(1); this.prBuildHistory; this.refresh }

	inBus_ { arg b;
		inBus = b;
		corrSynth !? { corrSynth.set(\inbus, b) };
		scopeSynth !? { scopeSynth.set(\inbus, b) };
	}

	// these change the shape of the analysis, so the synth has to restart
	numBands_ { arg n; this.prReshape({ numBands = n.clip(2, 64) }) }
	// width multiplier around the spacing-derived default; 1.0 tiles exactly
	bandQ_ { arg q; this.prReshape({ bandQ = q.clip(0.05, 4) }) }
	integration_ { arg sec; this.prReshape({ integration = sec.clip(0.001, 1) }) }
	freqRange_ { arg lo, hi;
		this.prReshape({
			minFreq = lo.max(1);
			maxFreq = hi.min((server.sampleRate ? 44100) / 2);
		});
	}

	prReshape { arg changes;
		var wasRunning = isRunning;
		if(wasRunning) { this.stop };
		changes.value;
		this.prBuildBands;
		if(wasRunning) { this.start };
		this.refresh;
	}

	// Print what the analysis is actually seeing, for when the picture and
	// your ears disagree. If the per-band positions spread both ways but the
	// display does not, the display is at fault; if they really do sit to one
	// side, the signal does.
	fieldReport {
		var n, lu, lhs, rhs;
		lu = lobeUp;
		n = lu.size;
		lhs = lu[0 .. (n.div(2) - 1)].sum;
		rhs = lu[(n.div(2) + 1) .. (n - 1)].sum;
		"StereoPhaseView field report\n".post;
		"  correlation % | balance % | rmsL % rmsR %\n".postf(
			correlation.round(0.001), this.balance.round(0.001),
			rmsL.round(0.00001), rmsR.round(0.00001));
		"  lobe energy: left % | right % | ratio %\n".postf(
			lhs.round(1e-9), rhs.round(1e-9),
			if(rhs > 1e-15) { (lhs / rhs).round(0.001) } { "inf" });
		"  (balance and lobe ratio near 1 mean the FIELD is centred;\n"
		"   if the picture still leans, the picture is wrong)\n".post;
		"     i      Hz       levL       levR     pos    corr\n".post;
		numBands.do { |i|
			"  %  %  %  %  %  %\n".postf(
				i.asString.padLeft(4),
				bandFreqs[i].round(1).asString.padLeft(7),
				bandL[i].round(0.00001).asString.padLeft(10),
				bandR[i].round(0.00001).asString.padLeft(10),
				this.bandPosition(i).round(0.01).asString.padLeft(6),
				bandCorr[i].round(0.01).asString.padLeft(6));
		};
	}

	info {
		"StereoPhaseView\n"
		"  mode    : %\n"
		"  bands   : % from % to % Hz (Q %, integration % s = % samples)\n"
		"  now     : correlation %, balance %, rms L % R %\n"
		"  view    : % fps, dB % .. %, persistence %\n".postf(
			mode, numBands, minFreq.round(0.1), maxFreq.round(0.1), bandQ,
			integration, this.prIntegrationSamples,
			correlation.round(0.001), this.balance.round(0.001),
			rmsL.round(0.0001), rmsR.round(0.0001),
			fps, dbFloor, dbCeil, persistence);
	}
}
