/*
=========================================================================
 Configurable-colour spectral displays.
   ScrollSpectrogram  - time on x, two interchangeable data paths
   MelBandsView       - frequency on x, current frame only
   MFCCView           - cepstral coefficients, scrolling or bipolar bars
 Evaluate blocks one at a time (Cmd-Return inside each parenthesis).

 Classes live in ./classes, symlinked into Extensions as
 "Analysis-Spectrogram". After editing them: Language > Recompile (Cmd-Shift-L).
=========================================================================
*/

// ---- 0. boot + a test signal to look at -------------------------------
(
s.waitForBoot({
	SynthDef(\specTest, { |amp = 0.2|
		var sweep = SinOsc.ar(LFNoise0.kr(0.5).exprange(200, 6000), 0, 0.2);
		var noise = BPF.ar(PinkNoise.ar(2), LFNoise1.kr(0.2).exprange(400, 8000), 0.1);
		var harm  = Mix(Array.fill(6, { |i|
			SinOsc.ar(110 * (i + 1), 0, 0.1 / (i + 1)) }));
		var clicks = Decay2.ar(Impulse.ar(2), 0.001, 0.08) * WhiteNoise.ar(0.3);
		Out.ar(0, ((sweep + noise + harm + clicks) * amp).dup);
	}).add;
});
)

x = Synth(\specTest);      // run this so there is something to see
x.free;


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
   Stacked - spectrogram, live bands, cepstrogram
   ===================================================================== */
(
~w = Window("spectrogram + bands + mfcc", Rect(100, 60, 900, 880)).front;
~p1 = CompositeView(~w, Rect(0, 0, 900, 400)).resize_(5);
~p2 = CompositeView(~w, Rect(0, 405, 900, 220)).resize_(5);
~p3 = CompositeView(~w, Rect(0, 630, 900, 240)).resize_(5);
~a = ScrollSpectrogram(~p1, ~p1.bounds, source: \mel, numBands: 192,
	bufSize: 2048, numRows: 360, seconds: 10, fps: 30, map: \viridis,
	dbFloor: -95, dbCeil: -10, minFreq: 30, maxFreq: 18000);
~m = MelBandsView(~p2, ~p2.bounds, numBands: 48, minFreq: 30, maxFreq: 18000,
	map: \viridis, dbFloor: -95, dbCeil: -10, fps: 45);
~f = MFCCView(~p3, ~p3.bounds, numCoeffs: 13, startCoeff: 1,
	map: \coolwarm, mode: \scroll, seconds: 10, fps: 30);
~a.start; ~m.start; ~f.start;
~w.onClose_({ ~a.stop; ~m.stop; ~f.stop });
)

~a.map_(\magma); ~m.map_(\magma);
~a.dbRange_(-110, -20); ~m.dbRange_(-110, -20);
(~a.stop; ~m.stop; ~f.stop; ~w.close;)
