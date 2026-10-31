// Installs classes/ into a SuperCollider Extensions directory, as a copy.
//
// Select all and evaluate (Cmd-Return). A window asks where to install:
//   User       Platform.userExtensionDir   — just you, no admin rights needed
//   System     Platform.systemExtensionDir — all users, may need admin rights
//   Choose...  any folder SuperCollider compiles from
//
// Then recompile the class library (Cmd-Shift-L).
//
// Re-running replaces a previous copy in the same place. It refuses to install
// if any of these classes is already compiled from somewhere else (an older
// copy, or the development symlink in README.md), since two definitions of one
// class stop the library compiling. Remove that one first.

(
var name = "Analysis-Spectrogram";
var src = thisProcess.nowExecutingPath.dirname +/+ "classes";
var files = (src +/+ "*.sc").pathMatch;
var install, win;

install = { |extDir|
	var dest = extDir +/+ name;
	var destReal, conflicts;

	if(File.exists(extDir).not) { File.mkdir(extDir) };
	destReal = if(File.exists(dest)) { File.realpath(dest) };

	// A symlink here points at the working copy; clearing it would delete the source.
	if(File.type(dest) == \symlink) {
		"Not installed: % is a symlink to %. Remove the link first.".format(dest, destReal).warn;
	} {
		conflicts = files.collect { |f|
			var cls = f.basename.splitext[0].asSymbol.asClass;
			var where = cls !? { File.realpath(cls.filenameSymbol.asString.dirname) };
			if(where.notNil and: { where != destReal }) { cls.name -> where }
		}.reject(_.isNil);

		if(conflicts.notEmpty) {
			"Not installed: these classes are already compiled from elsewhere:".warn;
			conflicts.do { |c| "  % — %".format(c.key, c.value).postln };
			"Remove that copy, recompile, and run this again.".postln;
		} {
			if(File.exists(dest)) { File.deleteAll(dest) };
			File.mkdir(dest);
			files.do { |f| File.copy(f, dest +/+ f.basename) };
			"Installed % classes to %".format(files.size, dest).postln;
			"Now recompile the class library: Cmd-Shift-L".postln;
		};
	};
};

if(files.isEmpty) {
	"No .sc files found in %".format(src).warn;
} {
	win = Window("Install %".format(name), Rect(200, 400, 460, 150)).front;
	win.layout = VLayout(
		StaticText().string_("Install % classes from\n%".format(files.size, src)),
		Button().states_([["User:  " ++ Platform.userExtensionDir]])
			.action_({ win.close; install.(Platform.userExtensionDir) }),
		Button().states_([["System:  " ++ Platform.systemExtensionDir]])
			.action_({ win.close; install.(Platform.systemExtensionDir) }),
		Button().states_([["Choose folder..."]])
			.action_({
				win.close;
				FileDialog({ |paths| install.(paths[0]) }, fileMode: 2);
			}),
	);
};
)
