/*
=========================================================================
 Configurable-colour spectral displays.
   ScrollSpectrogram  - time on x, two interchangeable data paths
   MelBandsView       - frequency on x, current frame only
   MFCCView           - cepstral coefficients, scrolling or bipolar bars
   StereoPhaseView    - stereo field and phase, PAZ-style polar + goniometer
 Evaluate blocks one at a time (Cmd-Return inside each parenthesis).

 Classes live in ./classes, symlinked into Extensions as
 "AnalysisViews". After editing them: Language > Recompile (Cmd-Shift-L).
=========================================================================
*/

// ---- 0. boot + a test signal to look at -------------------------------
(
s.waitForBoot({
	SynthDef(\specTest, { |amp = 0.2|
		var sweep = SinOsc.ar(LFNoise0.kr(0.5).exprange(200, 6000), 0, 0.2);
		var noise = BPF.ar(PinkNoise.ar(2), LFNoise1.kr(0.2).exprange(400, 8000), 0.1);
		// var harm  = Mix(Array.fill(6, { |i|
		// SinOsc.ar(110 * (i + 1), 0, 0.1 / (i + 1)) }));
		var harm = Splay.arFill(6, { |i|
			SinOsc.ar(110 * (i + 1), 0, 0.5 / (i + 1)) });

		var clicks = Decay2.ar(Impulse.ar(2), 0.001, 0.08) * WhiteNoise.ar(0.3);
		// Out.ar(0, ((sweep + noise + harm + clicks) * amp));
		Out.ar(0, (harm * amp));
	}).add;
});
)


(

	SynthDef(\specTest, { |amp = 0.2|
		var sweep = SinOsc.ar(LFNoise0.kr(0.5).exprange(200, 6000), 0, 0.2);
		var noise = BPF.ar(PinkNoise.ar(2), LFNoise1.kr(0.2).exprange(400, 8000), 0.1);
		// var harm  = Mix(Array.fill(6, { |i|
		// SinOsc.ar(110 * (i + 1), 0, 0.1 / (i + 1)) }));
		var harm = Splay.arFill(16, { |i|
			SinOsc.ar(110 * (i + 1), 0, 0.5 / (i + 1)) });

		var clicks = Decay2.ar(Impulse.ar(2), 0.001, 0.08) * WhiteNoise.ar(0.3);
	var sig = (sweep + noise + harm + clicks) * amp;
	Out.ar(0, [sig * MouseX.kr(pi.neg,pi), sig]);
	// Out.ar(0, (harm * amp));
	}).add;
)

x = Synth(\specTest);      // run this so there is something to see
x.free;

/* A warning about Splay + a falling amplitude series, since the test
   signal above uses one.

   Splay places array element 0 hard LEFT and the last element hard RIGHT.
   With amp = 0.5/(i+1) the loudest partial is element 0, so the loudest
   thing in the patch is pinned hard left. Measured on these 16 harmonics:

     amp 0.5/(i+1), in order    balance -0.615   rmsL/rmsR 4.2 : 1
     same spectrum, scrambled   balance -0.063   rmsL/rmsR 1.14 : 1
     equal amplitudes           balance  0.0     rmsL/rmsR 1.0 : 1

   Element 0 alone carries 63% of the total energy, and the first three
   carry 86%. So that patch really is about 4:1 left-heavy - if the stereo
   display leans left on it, the display is right.

   To keep the 1/(i+1) spectrum but spread it across the field, scramble
   the array so loud and quiet partials are not sorted by position: */
(
~even = {
	Splay.ar(Array.fill(16, { |i| SinOsc.ar(110 * (i + 1), 0, 0.5 / (i + 1)) }).scramble) * 0.2
}.play;
)
~even.free;


// ---- look at the available gradients ---------------------------------
SpecColorMap.plotAll;
SpecColorMap.names;
SpecColorMap(\turbo).plot;
SpecColorMap(\magma, gamma: 0.5).plot;          // gamma < 1 lifts quiet detail
SpecColorMap([Color.black, Color.blue, Color.cyan, Color.white]).plot;



/* =====================================================================
   A - ScrollSpectrogram, source: \fft
   Raw FFT bins pulled over OSC. True dBFS readout, log/mel/linear
   frequency axis, axis labels and cursor readout.
   (Resizing the window rescales and keeps the history; changing
   seconds_ or fps_ has to rebuild the buffer, so that one clears.)
   ===================================================================== */
(
b = ScrollSpectrogram(
	source: \fft,
	bufSize: 2048,
	numRows: 400,
	freqScale: \log,
	minFreq: 30,
	maxFreq: 18000,
	seconds: 12,
	fps: 30,
	map: \viridis,
	dbFloor: -95,
	dbCeil: -10
);
b.start;
)

b.info;

// colour
b.map_(\magma);
b.map_(\inferno);
b.map_(\sox);
b.map_(\ice);
b.gamma_(0.65);

// dynamic range - this is what the quark cannot do
b.dbRange_(-110, -20);          // see deep into the noise floor
b.dbRange_(-60, -6);            // only the loud stuff
b.dbRange_(-95, -10);

// frequency axis
b.freqScale_(\log);
b.freqScale_(\mel);
b.freqScale_(\lin);
b.freqRange_(30, 8000);
b.freqRange_(20, 20000);

// time
b.seconds_(30);                 // long view   (rebuilds buffer -> clears)
b.seconds_(5);                  // detail      (ditto)
b.fps_(60);                     // ~87/s is the ceiling on the \fft path
b.fps_(15);

b.numRows_(600);                // vertical resolution, independent of bufSize

// interaction: click-drag in the window for a Hz / dB readout,
// SPACE freezes, "c" clears, "s" writes a PNG
b.freeze; b.unfreeze;
b.snapshot;                     // -> ~/spectrogram-<date>.png
b.showGrid_(false);
b.showAxis_(false);

b.stop;



/* =====================================================================
   B - ScrollSpectrogram, source: \mel
   Same renderer, analysis done on the server by FluidMelBands and read
   through shared memory. No OSC round trip per frame. Band count is
   independent of FFT size, so a big window costs nothing extra to move.
   ===================================================================== */
(
c = ScrollSpectrogram(
	source: \mel,
	numBands: 192,          // == vertical detail; mel-spaced
	bufSize: 4096,          // analysis window; does NOT affect transfer size
	numRows: 400,
	minFreq: 30,
	maxFreq: 18000,
	seconds: 20,
	fps: 60,                // try high frame rates here
	map: \inferno,
	dbFloor: -90,
	dbCeil: 0
);
c.start;
)

c.info;
c.fps_(90);
c.map_(\turbo);
c.dbRange_(-80, -5);
c.numBands;                     // bands in; numRows is what gets drawn
c.stop;



/* =====================================================================
   C - MelBandsView
   The "right now" view: frequency on x, current frame only. Same
   shared-memory path as B, so it is cheap to run alongside a
   spectrogram. Fast attack / slow release ballistics with peak hold.
   ===================================================================== */
(
m = MelBandsView(
	numBands: 48,
	minFreq: 30,
	maxFreq: 18000,
	bufSize: 2048,
	style: \bars,          // \bars | \line | \area
	colorMode: \level,     // \level | \freq | \flat
	map: \viridis,
	dbFloor: -80,
	dbCeil: 0,
	fps: 45
);
m.start;
)

m.info;

// look
m.style_(\area);
m.style_(\line);
m.style_(\bars);
m.colorMode_(\freq);           // colour by band rather than by level
m.colorMode_(\level);
m.map_(\magma);
m.map_(\turbo);
m.colorMode_(\flat); m.barColor_(Color(0.4, 0.9, 0.6));
m.gap_(0);                     // solid block of bars
m.gap_(2);

// resolution and range
m.numBands_(96);               // restarts the synth
m.numBands_(24);
m.numBands_(48);
m.freqRange_(40, 16000);
m.dbRange_(-90, 0);

// ballistics
m.attack_(0.005); m.release_(0.4);    // snappy up, slow down
m.attack_(0.05);  m.release_(0.05);   // literal, no smoothing feel
m.fps_(60);

// peaks: "p" toggles, "r" resets, "g" toggles the grid
m.showPeaks_(true);
m.peakHold_(2.0);
m.peakDecay_(12);              // dB per second once the hold expires
m.resetPeaks;

// read the numbers out
m.levels.round(0.1);
m.peakLevels.round(0.1);
m.bandFreqs.round(1);

m.stop;
m.close;



/* =====================================================================
   D - MFCCView
   Mel-frequency cepstral coefficients. Signed data, so it wants a
   diverging colour map (midpoint = zero). Coefficient 0 is overall
   energy and swamps the rest, so startCoeff defaults to 1.
   ===================================================================== */
SpecColorMap.divergingNames;          // the maps that suit signed data

(
f = MFCCView(
	numCoeffs: 13,
	numBands: 40,
	startCoeff: 1,        // 0 would add the energy term back
	minFreq: 30,
	maxFreq: 18000,
	map: \coolwarm,
	mode: \scroll,        // \scroll | \bars
	seconds: 15,
	fps: 30
);
f.start;
)

f.info;

// modes - "m" in the window toggles too
f.mode_(\bars);                 // current frame, bipolar
f.mode_(\scroll);               // cepstrogram over time

// colour
f.map_(\rdbu);
f.map_(\spectral);
f.map_(\piyg);
f.map_(\coolwarm);

// scaling. Coefficient magnitudes depend on material and numBands, so
// autoRange is on by default; "a" toggles it in the window.
f.autoRange_(true);
f.effectiveRange;              // what it settled on
f.range_(60);                  // pin it (turns autoRange off)
f.autoRange_(true);
f.autoDecay_(0.15);            // slower relax = steadier scale

// analysis shape (these restart the synth)
f.numCoeffs_(20);
f.numBands_(26);
f.startCoeff_(0);              // include c0 and watch it dominate
f.startCoeff_(1);

// time
f.seconds_(30);
f.fps_(45);

// read the numbers
f.coeffs.round(0.01);
f.coeffIndices;

// "c" clears, "g" toggles grid; click-drag for a coefficient readout
f.clear;
f.stop;
f.close;



/* =====================================================================
   E - StereoPhaseView
   Stereo field and phase, after the Waves PAZ Position panel. The
   \position mode bins every band's energy by the DIRECTION its L/R
   balance implies, so you get a readable lobe: narrow spike for mono,
   broad fan for a wide mix, humps at the edges for hard-panned.
   Out-of-phase energy shows as a red lobe BELOW the horizon.
   Needs a stereo source on inBus (reads 2 consecutive buses).
   ===================================================================== */
(
p = StereoPhaseView(
	numBands: 16,
	minFreq: 40,
	maxFreq: 16000,
	inBus: 0,
	mode: \position,       // \position | \gonio | \bands | \history
	map: \viridis,
	dbFloor: -60,
	dbCeil: 0,
	fps: 30,
	integration: 0.3       // averaging window, seconds
);
p.start;
)

p.info;

// modes - "m" in the window cycles them
p.mode_(\position);        // PAZ-style polar field + correlation meter
p.mode_(\gonio);           // Lissajous: mono vertical, out-of-phase horizontal
p.mode_(\bands);           // correlation per band, diverging colour
p.mode_(\history);         // correlation over time

// the field itself
p.showBands_(true);        // overlay per-band spokes - which material, where
p.showBands_(false);       // just the lobe - how much, from where
p.lobeRange_(36);          // dB range the lobe spans; lower = more selective
p.lobeRange_(60);          // wider = fuller looking, less discriminating
p.dirBins_(121);           // smoother lobe
p.autoScale_(true);        // normalise to the loudest direction

// look
// The map runs OUT FROM THE CENTRE: centre takes the bottom of the map,
// hard left and hard right both take the top. Symmetric about C, so the
// colour says how far off centre energy is, not which side it is on.
p.map_(\jet);
p.map_(\turbo);
p.map_(\viridis);
p.colorBy_(\freq);          // instead tint each direction by its mean pitch
p.colorBy_(\spread);        // back to centre-outward (default)
p.directionTones.round(0.01);   // 0 low .. 1 high, per direction bin
p.corrMap_(\rdbu);         // correlation colour, diverging
p.persistence_(0.9);       // goniometer phosphor trail
p.scopeColor_(Color(1, 0.8, 0.3));
p.dbRange_(-110, -10);

// analysis (these restart the synth)
p.numBands_(24);
// Correlation is a windowed estimate, so it has variance - and bass-heavy
// material has lots of it. Measured on two UNCORRELATED sources (true
// correlation 0): pink noise at 0.05 s reads +/-0.3, at 0.5 s +/-0.15;
// white noise at 0.05 s is already +/-0.02. Hence the 0.3 s default.
p.integration_(0.05);      // fast, but jittery on anything bass-heavy
p.integration_(0.5);       // steady, slower to react
p.integration_(0.3);
p.bandQ_(0.5);             // wider bands
p.freqRange_(60, 12000);

// read the numbers
p.correlation;             // -1 .. +1
p.balance;                 // -1 left, 0 centre, +1 right
p.bandCorrelations.round(0.01);
p.bandFreqs.round(1);

p.stop;
p.close;


/* ---------------------------------------------------------------------
   CALIBRATION - is the view leaning, or is the material?

   Equal energy mirrored about centre: 400 Hz hard left, 1600 Hz hard
   right, 800 Hz up the middle. Different frequencies each side, but the
   display bins by DIRECTION, so the picture must come out symmetric:
   a centre lobe with matching pairs either side.

   Measured on the real on-screen window this renders with 14588 units of
   ink left of centre against 14613 right - a ratio of 0.998. If yours
   looks symmetric, the view is fine and your material is simply
   left-weighted. If it does not, something is wrong - say what window
   size you are using.
   --------------------------------------------------------------------- */
(
~cal = {
	Pan2.ar(SinOsc.ar(400,  0, 0.2), -0.8)
	+ Pan2.ar(SinOsc.ar(1600, 0, 0.2), 0.8)
	+ Pan2.ar(SinOsc.ar(800,  0, 0.2), 0)
}.play;
)
~cal.free;

// Worth knowing about Splay: it spreads its array across the image in
// ARRAY ORDER, so ascending frequencies put every low on the left and
// every high on the right. Overall balance still measures 0.0 - the two
// channels carry equal energy - but the lobe is coloured and shaped by
// where each band sits, so lows bunch to one side. Scrambling the array
// mixes the frequencies across the field:
(~t = { Splay.ar(SinOsc.ar([180, 330, 540, 880, 1400, 2300, 3700, 6000], 0, 0.12)) }.play;)
(~t.free; ~t = { Splay.ar(SinOsc.ar([180, 330, 540, 880, 1400, 2300, 3700, 6000].scramble, 0, 0.12)) }.play;)
~t.free;


// Sanity check it against known signals:
(
// One Select per channel. Selecting on an array of stereo pairs does NOT
// work - [[a,b],[c,d],[e,f]].flop is two arrays of three, so Select sees
// two options instead of three and the result collapses to left-only.
~t = { |which = 0|
	var n = PinkNoise.ar(0.3), n2 = oise.ar(0.3);
	[
		Select.ar(which, [n, n,     n ]),   // left
		Select.ar(which, [n, n.neg, n2])    // right
	]
}.play;
)
~t.set(\which, 0);   // identical  -> correlation +1, vertical line in \gonio
~t.set(\which, 1);   // inverted   -> correlation -1, horizontal line
~t.set(\which, 2);   // unrelated  -> correlation  0, round cloud
~t.free;

// And the field itself - Splay spreads an array across the image in
// ARRAY ORDER, so ascending frequencies put lows left and highs right
// (overall balance stays 0.0; it is the distribution that shifts):
(~t = { Splay.ar(SinOsc.ar([180, 330, 540, 880, 1400, 2300, 3700, 6000], 0, 0.12)) }.play;)
~t.free;
(~t = { var m = Mix(SinOsc.ar([180, 330, 540, 880], 0, 0.12)); [m, m] }.play;)      // mono
~t.free;
(~t = { var m = Mix(SinOsc.ar([180, 330, 540, 880], 0, 0.12)); [m, m.neg] }.play;)  // inverted
~t.free;



/* =====================================================================
   Stacked - spectrogram, live bands, cepstrogram
   ===================================================================== */
(
// Omit the bounds argument when embedding: each view fills its pane.
// Passing ~p2.bounds would be wrong - that rect is where ~p2 sits in the
// WINDOW, and using it inside ~p2 pushes the view off the bottom.
//
// The spectrogram and the bands view below ask for the SAME mel analysis
// (96 bands, 30-18000 Hz, window 2048), so SpecAnalysisFeed hands them one
// shared synth instead of running the STFT twice. Give them different
// numbers and they each get their own feed, which is correct but costs
// another analysis. SpecAnalysisFeed.report shows what is shared.
~w = Window("spectrogram + bands + mfcc", Rect(100, 60, 900, 880)).front;
~p1 = CompositeView(~w, Rect(0, 0, 900, 400)).resize_(5);
~p2 = CompositeView(~w, Rect(0, 405, 900, 220)).resize_(5);
~p3 = CompositeView(~w, Rect(0, 630, 900, 240)).resize_(5);
~a = ScrollSpectrogram(~p1, source: \mel, numBands: 96,
	bufSize: 2048, numRows: 360, seconds: 10, fps: 30, map: \viridis,
	dbFloor: -95, dbCeil: -10, minFreq: 30, maxFreq: 18000);
~m = MelBandsView(~p2, numBands: 96, minFreq: 30, maxFreq: 18000,
	bufSize: 2048, map: \viridis, dbFloor: -95, dbCeil: -10, fps: 45);
~f = MFCCView(~p3, numCoeffs: 13, startCoeff: 1,
	map: \coolwarm, mode: \scroll, seconds: 10, fps: 30);
~a.start; ~m.start; ~f.start;
~w.onClose_({ ~a.stop; ~m.stop; ~f.stop });
)

SpecAnalysisFeed.report;   // one mel feed with two users, one mfcc feed

~a.map_(\magma); ~m.map_(\magma);
~a.dbRange_(-110, -20); ~m.dbRange_(-110, -20);
(~a.stop; ~m.stop; ~f.stop; ~w.close;)
