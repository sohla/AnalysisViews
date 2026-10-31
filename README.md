# Spectral displays for SuperCollider

Configurable-colour real-time analysis views, written because the Spectrogram
quark is greyscale, fixed at 17 brightness steps, and clips everything below
magnitude 1.0 — a hard floor of −48.2 dBFS at `bufSize` 1024.

| class | shows | axis |
|---|---|---|
| `ScrollSpectrogram` | scrolling spectrogram | time × frequency |
| `MelBandsView` | current frame's band levels | frequency × level |
| `MFCCView` | mel-frequency cepstral coefficients | time × coefficient, or bipolar bars |
| `StereoPhaseView` | stereo field and phase | polar direction, goniometer, correlation |
| `SpecColorMap` | 21 gradients shared by all of the above | — |
| `SpecAnalysisFeed` | one FluCoMa analysis, shared between views | — |

## Install

The classes live in `classes/`. Open `install.scd`, select all and evaluate. A
window asks where to put them:

- **User** — `Platform.userExtensionDir`, just you, no admin rights needed
- **System** — `Platform.systemExtensionDir`, all users, may need admin rights
- **Choose folder...** — any folder SuperCollider compiles from

They are copied to an `Analysis-Spectrogram` folder there. Running it again
replaces that copy. It refuses to install if any of these classes is already
compiled from somewhere else — an older copy, or the symlink below — because
two definitions of one class stop the library compiling. Remove that one first.

To work on the classes instead, symlink `classes/` into the Extensions directory
so edits take effect without reinstalling:

```
ln -s "$PWD/classes" \
  "$HOME/Library/Application Support/SuperCollider/Extensions/Analysis-Spectrogram"
```

**Then recompile the class library — `Cmd-Shift-L`.** SuperCollider will not
notice an edited `.sc` file until you do. This is the single most common reason
a change appears not to have taken effect.

`StereoPhaseView` needs a stereo source; the `\mel` source of
`ScrollSpectrogram`, plus `MelBandsView` and `MFCCView`, need
[FluCoMa](https://www.flucoma.org/) and a server on localhost (they read
through shared memory).

Work through `spectrogram-options.scd` a block at a time.

## Quick start

```supercollider
ScrollSpectrogram.new.start;                      // time × frequency
MelBandsView.new.start;                           // live band levels
MFCCView.new.start;                               // cepstrogram
StereoPhaseView.new.start;                        // stereo field

SpecColorMap.plotAll;                             // see all 21 gradients
SpecColorMap.divergingNames;                      // the signed-data ones
```

Every view takes a `parent` and embeds into a layout. **Omit the `bounds`
argument when embedding** — a view's bounds are relative to its parent, so
passing `somePane.bounds` places the child at that pane's *window* offset,
inside the pane, and it lands off the bottom.

```supercollider
~p = CompositeView(~win, Rect(0, 405, 900, 220));
~m = MelBandsView(~p);                            // right
~m = MelBandsView(~p, ~p.bounds);                 // wrong — invisible
```

## Shared analysis

`MelBandsView`, `MFCCView` and `ScrollSpectrogram(source: \mel)` all need a
FluCoMa analysis. Rather than each running its own, they acquire a
`SpecAnalysisFeed`: a reference-counted synth plus control bus, identified by
everything that affects its output — analysis type, server, input bus and
parameters. Views asking for the same analysis share one synth; a view wanting
something different still gets its own. The last release frees it.

Stacking a spectrogram, a bands view and a cepstrogram therefore costs **two**
analyses rather than three, provided the first two agree on their parameters:

```supercollider
SpecAnalysisFeed.report;
// SpecAnalysisFeed: 2 active
//   mel_localhost_0_96_30_18000_2048 : 96 channels, 2 users, bus 0
//   mfcc_localhost_0_13_40_1_30_18000_2048 : 13 channels, 1 user, bus 96
```

Sharing only happens when the parameters match — a 96-band spectrogram and a
48-band meter are two different analyses and get two feeds, which is correct
but costs the extra STFT. The stacked example in `spectrogram-options.scd` puts
both on 96 bands so they share.

Changing `inBus`, `numBands` or the frequency range changes which analysis is
wanted, so those setters now swap feeds, which means a brief restart.

## Measured behaviour

Numbers taken on this machine, 44.1 kHz, block 512.

**Data paths.** The two sources differ by far more than convenience:

| path | cost | notes |
|---|---|---|
| `\fft` — FFT buffer over OSC | ~11.5 ms a frame | latency-bound, not bandwidth-bound: 512, 1024 and 2048 floats all returned at ~87/s, so this tops out near 87 fps at any window size |
| `\mel` — control bus via shared memory | ~0.002 ms a read | ~400,000/s; transfer is effectively free, frame rate is bounded only by repaint |

**Level calibration.** On `\fft` the dB readout is true dBFS: a 0.3-amplitude
sine (−10.5 dBFS) measured −10.5 dB at 1005 Hz with `bufSize` 4096, and −11.6
at 2048 — the difference being bin-straddling scalloping loss. On `\mel` the
same tone measured −14.9, because a normalised triangle filter spreads one tone
across its band. Treat `\mel` levels as a perceptual display rather than
absolute dBFS; trim with `dbOffset_` if the two need to agree.

**Correlation integration.** Correlation is a windowed estimate whose variance
depends on how much low-frequency energy the material carries. On two
*uncorrelated* sources, where the true correlation is 0:

| source | window | std dev |
|---|---|---|
| white noise | 0.05 s | 0.019 |
| white noise | 0.50 s | 0.008 |
| pink noise | 0.05 s | **0.319** |
| pink noise | 0.50 s | 0.154 |

Music is pink-ish, so a 50 ms window makes the meter swing ±0.3 on nothing at
all. Hence the 0.3 s default, roughly where hardware correlation meters sit.
Fully correlated and fully inverted material pins at exactly ±1 at any window
length.

## StereoPhaseView

Four renderings of one control bus, cycled with `m`:

- **`\position`** — polar stereo field, after the Waves PAZ Position panel.
  Every band's energy is binned by the *direction* its L/R balance implies,
  giving a continuous lobe: a narrow upright spike for mono, a broad fan for a
  wide mix, humps at the edges for hard-panned material. The colour map runs
  **out from the centre** — centre takes the bottom of the map, hard left and
  hard right both the top — so colour says how far off centre energy is, not
  which side it is on. `colorBy_(\freq)` tints by the mean pitch arriving from
  each direction instead.
- **`\gonio`** — Lissajous vectorscope with phosphor persistence. Mono reads
  vertical, out of phase horizontal.
- **`\bands`** — correlation per band, diverging colour.
- **`\history`** — correlation over time.

Out-of-phase energy appears as a **red lobe below the horizon**, carrying only
the anti-correlated share of each band (`energy × max(0, −correlation)`). It
stays empty for in-phase *and* for merely-uncorrelated-wide material, so
anything below the line is genuinely phase-cancelling.

One set of `RunningSum`s per band over L², R² and L·R yields both the band
levels and the correlation, so nothing is computed twice.

### If the field looks lopsided

It is usually the signal. `p.fieldReport` prints correlation, balance, the
lobe's left/right energy split and every band's level and position, which
separates the two cases.

`Splay` places array element 0 **hard left** and the last element hard right,
so a falling amplitude series pins the loudest partial hard left:

| 16 harmonics, `Splay` | balance | rmsL/rmsR |
|---|---|---|
| `amp = 0.5/(i+1)`, in order | −0.615 | 4.2 : 1 |
| same spectrum, array scrambled | −0.063 | 1.14 : 1 |
| equal amplitudes | 0.0 | 1.0 : 1 |

Element 0 alone carries 63% of that signal's energy and the first three carry
86%, so it really is about 4:1 left-heavy. It is the *amplitude* ordering that
does this, not the frequency ordering — with equal amplitudes, balance measures
0.0.

To confirm the view itself is honest, play the calibration signal in
`spectrogram-options.scd`: equal energy mirrored about centre, which must draw a
centre lobe with matching pairs either side. Captured from the live window it
renders with 14588 units of ink left of centre against 14613 right, a ratio of
0.998.

## Colour maps

21 gradients, baked to an `Int32Array` lookup table so a frame costs one array
index per bin. 16 sequential (`viridis`, `magma`, `inferno`, `plasma`,
`cividis`, `turbo`, `sox`, `audacity`, `jet`, `hot`, `grey`, `greyInv`, `amber`,
`ice`, `matrix`, `paper`) and 5 diverging (`coolwarm`, `rdbu`, `spectral`,
`piyg`, `bwr`) for signed data such as correlation and cepstral coefficients.

```supercollider
SpecColorMap(\viridis).plot;
SpecColorMap(\magma, gamma: 0.6).plot;            // gamma < 1 lifts quiet detail
SpecColorMap([Color.black, Color.blue, Color.white]).plot;   // your own stops
```
