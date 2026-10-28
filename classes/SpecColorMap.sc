// SpecColorMap - configurable colour gradients + tone mapping for spectrograms.
//
// A SpecColorMap turns a normalised 0..1 intensity into a Color, and bakes the
// whole mapping into an Int32Array lookup table of pixel values that a
// spectrogram can index per bin per frame (one array lookup, no arithmetic).
//
//   SpecColorMap.names;                      // list built-ins
//   SpecColorMap(\viridis).plot;             // look at one
//   SpecColorMap(\magma, gamma: 0.6).asLut;  // Int32Array[256]
//   SpecColorMap([Color.black, Color.red, Color.yellow]).plot;

SpecColorMap {

	classvar <>maps;

	var <stops;          // Array of [position(0..1), Color]
	var <name;           // source built-in, when it came from one
	var <gamma = 1.0;     // < 1 lifts low levels, > 1 crushes them
	var <alpha = 1.0;
	var <size = 256;
	var lutCache;

	*initClass {
		// Each entry: evenly spaced hex anchors, low -> high intensity.
		maps = IdentityDictionary[

			// --- perceptually uniform (matplotlib) ---
			\viridis -> [ "440154", "482878", "3e4a89", "31688e", "26828e",
				"1f9e89", "35b779", "6ece58", "b5de2b", "fde725" ],
			\magma -> [ "000004", "180f3d", "440f76", "721f81", "9e2f7f",
				"cd4071", "f1605d", "fd9668", "feca8d", "fcfdbf" ],
			\inferno -> [ "000004", "170c3a", "420a68", "6b186e", "932667",
				"bc3754", "dd513a", "f3771a", "fca50a", "f6d543", "fcffa4" ],
			\plasma -> [ "0d0887", "41049d", "6a00a8", "8f0da4", "b12a90",
				"cc4778", "e16462", "f2844b", "fca636", "fcce25", "f0f921" ],
			\cividis -> [ "00224e", "123570", "3b496c", "575d6d", "707173",
				"8a8678", "a59c74", "c3b369", "e1cc55", "fee838" ],
			\turbo -> [ "30123b", "4145ab", "4675ed", "39a2fc", "1bcfd4",
				"24eca6", "61fc6c", "a4fc3b", "d1e834", "f3c63a", "fe9b2d",
				"f36315", "d93806", "b11901", "7a0402" ],

			// --- classic spectrogram looks ---
			\sox -> [ "000000", "1b0c41", "4a0c6b", "781c6d", "a52c60",
				"cf4446", "ed6925", "fb9a06", "f7d13d", "fcffa4" ],
			\audacity -> [ "000000", "1a0a4a", "47008f", "7b0090", "b20069",
				"dc2f2f", "f06d00", "ffb400", "ffe800", "ffffff" ],
			\jet -> [ "000080", "0000ff", "0080ff", "00ffff", "80ff80",
				"ffff00", "ff8000", "ff0000", "800000" ],
			\hot -> [ "000000", "3b0000", "7a0000", "b81e00", "ff5500",
				"ff9500", "ffce00", "ffff66", "ffffff" ],

			// --- diverging, for signed data (MFCCs, deltas). Midpoint is the
			// --- neutral colour, so map 0 to 0.5 when you use these.
			\coolwarm -> [ "3b4cc0", "6788ee", "9abbff", "c9d7f0", "edd1c2",
				"f7a789", "e26952", "b40426" ],
			\rdbu -> [ "053061", "2166ac", "4393c3", "92c5de", "d1e5f0",
				"f7f7f7", "fddbc7", "f4a582", "d6604d", "b2182b", "67001f" ],
			\spectral -> [ "5e4fa2", "3288bd", "66c2a5", "abdda4", "e6f598",
				"ffffbf", "fee08b", "fdae61", "f46d43", "d53e4f", "9e0142" ],
			\piyg -> [ "8e0152", "c51b7d", "de77ae", "f1b6da", "fde0ef",
				"f7f7f7", "e6f5d0", "b8e186", "7fbc41", "4d9221", "276419" ],
			\bwr -> [ "0000ff", "ffffff", "ff0000" ],

			// --- single-hue / duotone ---
			\grey -> [ "000000", "ffffff" ],
			\greyInv -> [ "ffffff", "000000" ],
			\amber -> [ "000000", "2a1400", "5c2f00", "944d00", "c97400",
				"eda300", "ffcf3d", "ffe999", "ffffff" ],
			\ice -> [ "000000", "00142b", "00305c", "00538f", "0d7fb8",
				"39abd6", "7fcfe8", "c2eaf5", "ffffff" ],
			\matrix -> [ "000000", "001a05", "00380f", "005a1c", "00812c",
				"0fab45", "48d070", "a0eeb0", "ffffff" ],
			\paper -> [ "ffffff", "e8e8ea", "c9cace", "a3a5ab", "797c85",
				"52555e", "2f3138", "141519", "000000" ],
		];
	}

	// Maps whose midpoint is the neutral value - the right choice for data
	// that goes both sides of zero.
	*divergingNames { ^#[\coolwarm, \rdbu, \spectral, \piyg, \bwr] }

	*names { ^maps.keys.asArray.sort }

	isDiverging { ^this.class.divergingNames.includes(this.name) }

	*new { arg spec, gamma = 1.0, alpha = 1.0, size = 256;
		^super.new.init(spec, gamma, alpha, size);
	}

	// spec may be: a Symbol naming a built-in, an Array of Colors (evenly
	// spaced), an Array of [pos, Color] pairs, a single Color (black -> col),
	// or an existing SpecColorMap (copied).
	init { arg spec, gammaArg, alphaArg, sizeArg;
		gamma = gammaArg; alpha = alphaArg; size = sizeArg;
		name = case
			{ spec.isKindOf(Symbol) } { spec }
			{ spec.isKindOf(String) } { spec.asSymbol }
			{ spec.isKindOf(SpecColorMap) } { spec.name };
		stops = this.class.parseStops(spec);
	}

	*parseStops { arg spec;
		var cols;
		^case
		{ spec.isKindOf(SpecColorMap) } { spec.stops.copy }
		{ spec.isKindOf(Symbol) or: { spec.isKindOf(String) } } {
			cols = maps[spec.asSymbol];
			if(cols.isNil) {
				Error("SpecColorMap: unknown map '%'. Known: %"
					.format(spec, this.names)).throw;
			};
			this.evenly(cols.collect(this.hexToColor(_)));
		}
		{ spec.isKindOf(Color) } { this.evenly([Color.black, spec]) }
		{ spec.isNil } { this.evenly([Color.black, Color.white]) }
		{ spec.isKindOf(SequenceableCollection) } {
			if(spec.first.isKindOf(SequenceableCollection)
				and: { spec.first.first.isNumber }) {
				// already [pos, Color] pairs
				spec.collect { |pair| [pair[0], this.asColor(pair[1])] }
					.sort { |a, b| a[0] <= b[0] };
			} {
				this.evenly(spec.collect(this.asColor(_)));
			};
		}
		{ Error("SpecColorMap: cannot read colour spec %".format(spec)).throw };
	}

	*asColor { arg x;
		^case
		{ x.isKindOf(Color) } { x }
		{ x.isKindOf(String) } { this.hexToColor(x) }
		{ x.isKindOf(SequenceableCollection) } { Color(*x) }
		{ Error("SpecColorMap: % is not a colour".format(x)).throw };
	}

	*evenly { arg colors;
		var n = colors.size;
		^colors.collect { |c, i| [ i / (n - 1).max(1), c ] };
	}

	*hexToColor { arg hex;
		var s = hex.asString.reject { |c| c == $# };
		^Color(
			("0x" ++ s[0..1]).interpret / 255,
			("0x" ++ s[2..3]).interpret / 255,
			("0x" ++ s[4..5]).interpret / 255
		);
	}

	// --- evaluation -------------------------------------------------------

	// Colour at a normalised position, gamma applied.
	at { arg frac;
		var f = frac.clip(0, 1) ** gamma, lo, hi, i;
		i = stops.detectIndex { |s| s[0] >= f } ? (stops.size - 1);
		if(i == 0) { ^stops[0][1].alpha_(alpha) };
		lo = stops[i - 1]; hi = stops[i];
		^blend(lo[1], hi[1], (f - lo[0]) / (hi[0] - lo[0]).max(1e-9))
			.alpha_(alpha);
	}

	// `size` colours, evenly spaced.
	asArray { ^size.collect { |i| this.at(i / (size - 1)) } }

	// `size` packed pixel integers - the hot path for a spectrogram.
	asLut {
		if(lutCache.isNil) {
			lutCache = Int32Array.newFrom(
				this.asArray.collect { |c| Image.colorToPixel(c) });
		};
		^lutCache;
	}

	// Same, but the index is a dB value: index i means
	// dbFloor + (i/(size-1)) * (dbCeil - dbFloor), clamped at both ends.
	// Use when the feeding code hands you an index already proportional to dB
	// over a fixed range (e.g. the Spectrogram quark's 0..255 == 0..63.75 dB).
	dbLut { arg dbFloor = -72, dbCeil = 0, indexFloor, indexCeil, n;
		var span;
		n = n ? size;
		indexFloor = indexFloor ? dbFloor;
		indexCeil = indexCeil ? dbCeil;
		span = (dbCeil - dbFloor).max(1e-9);
		^Int32Array.newFrom(
			n.collect { |i|
				var db = indexFloor.blend(indexCeil, i / (n - 1).max(1));
				Image.colorToPixel(this.at(((db - dbFloor) / span).clip(0, 1)));
			}
		);
	}

	gamma_ { arg val; gamma = val; lutCache = nil; }
	alpha_ { arg val; alpha = val; lutCache = nil; }
	size_  { arg val; size = val;  lutCache = nil; }
	stops_ { arg val; stops = this.class.parseStops(val); lutCache = nil; }

	reversed { ^this.class.new(stops.collect { |s| [1 - s[0], s[1]] }.reverse,
		gamma, alpha, size) }

	// --- inspection -------------------------------------------------------

	// One shared ramp painter, so plot and plotAll cannot drift apart.
	*prRampFunc { arg colors;
		^{ |v|
			var n = colors.size, w = v.bounds.width / n, h = v.bounds.height;
			colors.do { |c, i|
				Pen.color = c;
				// round outward so no seam shows between adjacent swatches
				Pen.addRect(Rect((i * w).floor, 0, w.roundUp(1), h));
				Pen.fill;
			};
		};
	}

	plot { arg name, bounds;
		var win = Window(name ? "SpecColorMap", bounds ?? { Rect(300, 300, 512, 80) });
		UserView(win, win.view.bounds)
			.resize_(5)
			.drawFunc_(this.class.prRampFunc(this.asArray));
		^win.front
	}

	*plotAll { arg bounds;
		var names = this.names;
		var win = Window("SpecColorMap - all",
			bounds ?? { Rect(200, 200, 560, (names.size * 26) + 20) });
		win.layout = VLayout(*names.collect { |n|
			HLayout(
				StaticText().string_(n).fixedWidth_(78).align_(\right),
				UserView()
					.fixedHeight_(18)
					.drawFunc_(this.prRampFunc(SpecColorMap(n).asArray))
			)
		});
		^win.front
	}

	printOn { arg stream;
		stream << "SpecColorMap(" << stops.size << " stops, gamma: " << gamma << ")";
	}
}
