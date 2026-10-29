// SpecAnalysisFeed - one FluCoMa analysis, shared by every view that wants it.
//
// MelBandsView, MFCCView and ScrollSpectrogram(source: \mel) each used to run
// their own FluidMelBands / FluidMFCC synth. Stack a spectrogram, a bands view
// and a cepstrogram in one window and the machine does the STFT three times
// over for the same audio.
//
// A feed is identified by everything that affects its output - analysis type,
// server, input bus and parameters - so two views asking for the same analysis
// get the same synth and the same control bus, while a view that wants
// something genuinely different still gets its own. Feeds are reference
// counted: the last release frees the synth and the bus.
//
//   f = SpecAnalysisFeed.melBands(s, inBus: 0, numBands: 40);
//   f.read;              // FloatArray, straight from shared memory
//   f.release;
//
//   SpecAnalysisFeed.report;     // what is running and who is sharing it
//
// Views do this for you; you only need it directly if you want the numbers
// without a GUI attached.

SpecAnalysisFeed {

	classvar <>all, cmdPeriodFunc;

	var <key, <server, <bus, <numChannels, <synth, <refs = 0;
	var <defName, <type, <params, ready = false;

	*initClass {
		all = IdentityDictionary.new;
	}

	*prEnsureCmdPeriod {
		cmdPeriodFunc ?? {
			// the server frees every synth on a reset, so drop the registry;
			// views re-acquire when they restart and get fresh feeds
			cmdPeriodFunc = { this.freeAll };
			CmdPeriod.add(cmdPeriodFunc);
		};
	}

	// --- the two analyses the views need -------------------------------

	*melBands { arg server, inBus = 0, numBands = 40, minFreq = 20,
		maxFreq = 20000, windowSize = 1024;
		^this.prAcquire(\mel, server, inBus,
			[numBands, minFreq, maxFreq, windowSize], numBands);
	}

	*mfcc { arg server, inBus = 0, numCoeffs = 13, numBands = 40,
		startCoeff = 0, minFreq = 20, maxFreq = 20000, windowSize = 1024;
		^this.prAcquire(\mfcc, server, inBus,
			[numCoeffs, numBands, startCoeff, minFreq, maxFreq, windowSize],
			numCoeffs);
	}

	// --- registry -------------------------------------------------------

	*prAcquire { arg typeArg, serverArg, inBus, paramsArg, nChan;
		var k, feed;
		serverArg = serverArg ? Server.default;
		k = this.prKey(typeArg, serverArg, inBus, paramsArg);
		feed = all[k];
		if(feed.isNil) {
			feed = super.new.init(k, typeArg, serverArg, inBus, paramsArg, nChan);
			all[k] = feed;
			this.prEnsureCmdPeriod;
			// count this acquirer BEFORE starting. prStart waits on
			// server.sync, and that yield lets the caller run on; if the
			// retain happened afterwards the "is anyone still waiting on
			// this?" check would see zero and quietly skip the Synth,
			// leaving a bus full of zeros.
			feed.prRetain;
			feed.prStart;
		} {
			feed.prRetain;
		};
		^feed
	}

	*prKey { arg typeArg, serverArg, inBus, paramsArg;
		^("%_%_%_%".format(typeArg, serverArg.name, inBus,
			paramsArg.collect({ |x| x.asString }).join("_"))
			.replace(".", "d").replace("-", "n").replace(" ", "")).asSymbol
	}

	init { arg k, typeArg, serverArg, inBus, paramsArg, nChan;
		key = k;
		type = typeArg;
		server = serverArg;
		params = [inBus] ++ paramsArg;
		numChannels = nChan;
		defName = ("specFeed_" ++ k).asSymbol;
		bus = Bus.control(server, numChannels);
		^this
	}

	prRetain { refs = refs + 1 }

	// --- the synth ------------------------------------------------------

	prStart {
		var inBus = params[0], p = params[1..];
		if(\FluidMelBands.asClass.isNil) {
			Error("SpecAnalysisFeed needs FluCoMa (FluidMelBands / FluidMFCC)."
			).throw;
		};

		switch(type,
			\mel, {
				SynthDef(defName, {
					Out.kr(bus, FluidMelBands.kr(InFeedback.ar(inBus),
						numBands: p[0], minFreq: p[1], maxFreq: p[2],
						normalize: 1, scale: 1,          // scale 1 == dB out
						windowSize: p[3], maxNumBands: p[0]));
				}).send(server);
			},
			\mfcc, {
				SynthDef(defName, {
					Out.kr(bus, FluidMFCC.kr(InFeedback.ar(inBus),
						numCoeffs: p[0], numBands: p[1], startCoeff: p[2],
						minFreq: p[3], maxFreq: p[4], windowSize: p[5],
						maxNumCoeffs: p[0], maxNumBands: p[1]));
				}).send(server);
			},
			{ Error("SpecAnalysisFeed: unknown type %".format(type)).throw }
		);

		forkIfNeeded {
			server.sync;
			// a release while we were waiting - do not leave a synth behind
			if(refs > 0 and: { bus.notNil }) {
				synth = Synth(defName, nil, server);
				ready = true;
			};
		};
	}

	// --- use ------------------------------------------------------------

	// Shared memory, no OSC round trip. Returns zeros until the synth is up.
	read {
		^if(bus.notNil) { bus.getnSynchronous(numChannels) } { nil }
	}

	isReady { ^ready }

	release {
		refs = refs - 1;
		if(refs <= 0) { this.free };
	}

	free {
		refs = 0;
		ready = false;
		try { synth.free }; synth = nil;
		try { bus.free };  bus = nil;
		all.removeAt(key);
	}

	*freeAll {
		all.values.copy.do { |f| f.prServerReset };
		all.clear;
	}

	// after a server reset the synth is already gone; keep the bus allocation
	// tidy but do not try to free a node that no longer exists
	prServerReset {
		refs = 0;
		ready = false;
		synth = nil;
		try { bus.free }; bus = nil;
	}

	*activeCount { ^all.size }

	*report {
		"SpecAnalysisFeed: % active\n".postf(all.size);
		all.keysValuesDo { |k, f|
			"  % : % channels, % user%, bus %\n".postf(
				k, f.numChannels, f.refs, if(f.refs == 1) { "" } { "s" },
				f.bus !? { f.bus.index } ? "-");
		};
	}

	printOn { arg stream;
		stream << "SpecAnalysisFeed(" << key << ", " << refs << " users)";
	}
}
