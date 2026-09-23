# Desktop Lyrics source integration

This branch keeps AM++'s Apple Music hooks, native TTML parser, lyric display,
and animation. Only automatic lyric resolution changes.

In Apple Music → Settings → AM++ module settings → Custom lyrics, enable
"Custom lyric replacement" and "Automatic real-time completion". An existing
explicitly disabled setting remains disabled after installing this build.

Priority during playback:

1. A lyric explicitly saved by the user in AM++.
2. Desktop Lyrics' QQ Music, NetEase, and LRCLIB search and ranking using the
   current Apple Music title, artist, album (when exposed), and duration.
3. AM++'s existing AMLL, Lunabeat, and AM-Lyrics sources.
4. Apple Music's own lyric pointer when no replacement is ready.

Desktop Lyrics' source translation is included when QQ Music or NetEase returns
one. Its word timing is converted into Apple Music Word-TTML; a source with only
line timing receives one timed span per line. Translation metadata uses the
format already accepted by AM++'s native parser, including a keyed placeholder
for each untranslated line. Apple Music then offers its own translation button.
AM++ settings show the source actually installed for the current song, including
whether the selected source supplied a translation. The custom API translation
settings are integrated. Offline ML Kit translation is experimental: model
downloads currently fail in the embedded package, so it should not be advertised
as working.

Automatic results remain separate from AM++'s manually configured mappings.
Only Desktop Lyrics results are cached; older AM++ automatic results cannot
hide a new Desktop Lyrics match. Existing `auto-cache` manifest entries are
also ignored for lyric replacement. All network requests and TTML parsing run on
the existing AM++ background executor.

Build with `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` on Windows.
The APK produced by Gradle is the AM++ module. Creating an NPatch embedded
Apple Music package requires the Apple Music split APKs and NPatch repackaging;
Gradle does not produce the embedded `.apks` by itself.

The embedded arm64 build was checked on Apple Music 6.5.3 (1599). For `群青`,
the matcher received title, artist, album and 248-second duration, selected QQ
Music, and Apple Music showed its translation button after every lyric line was
represented in the translation track. Other songs and devices still require
their own playback check.

## Sharing and updating

Public releases of this branch contain the modified AM++ source and its
standalone module APK. They do not contain Apple Music APKs or an embedded
Apple Music `.apks` archive. The standalone module alone is useful with a
compatible Xposed environment; it does not replace Apple Music or provide a
rootless installation.

For a rootless installation, obtain the matching Apple Music split APKs for
your own device, then use NPatch locally to embed this branch's module APK.
After patching, run `scripts/package-tcrrry-embedded.ps1` on the patched
output directory to create a `.apks` archive containing `base.apk` and the
matching ABI and density splits. Install the local archive with a split-package
installer such as SAI or NPatch. The current integration was tested with
Apple Music 6.5.3 (1599), arm64 and xxxhdpi; other variants need their own
splits and compatibility testing. An ordinary APK installer cannot install
the split archive. Keep the Apple Music binaries and resulting archive local;
Apple Music's license restricts redistribution of its software.

For the tested arm64/xxxhdpi variant, a local Windows example is:

```powershell
$NPatchCli = 'C:\path\to\npatch-cli.jar'
$AppleBase = 'C:\path\to\origin.apk'
$AppleArm64 = 'C:\path\to\split_config.arm64_v8a.apk'
$AppleDensity = 'C:\path\to\split_config.xxxhdpi.apk'
.\gradlew.bat :app:assembleDebug
java -jar $NPatchCli --embed .\app\build\outputs\apk\debug\app-debug.apk --npatch-keystore --output .\patched $AppleBase $AppleArm64 $AppleDensity
.\scripts\package-tcrrry-embedded.ps1 -InputDirectory .\patched -OutputFile .\Tcrrry-AMPP-local.apks
```

Use split APKs from one Apple Music installation and keep the same NPatch
signing key when making updates for an existing installation. NPatch may name
its patched base `origin-*-npatched.apk`; the packaging script expects this
name and the arm64/xxxhdpi split names. Check the files before packaging.

Keep the Tcrrry changes on the `tcrrry-lyrics` branch. For an upstream AM++
release, fetch its new commit, merge it into this branch, resolve conflicts,
run the project tests, and test settings and lyric injection on a phone. The
module must then be embedded into Apple Music again. For a new Apple Music
version, first follow `docs/apple-music-target-adaptation.md` to add and test
its exact hook profile; reusing a package built for 6.5.3 will not adapt it.
Use the same signing key for every embedded build intended to update an
existing installation. Different keys require uninstalling and lose host data.
