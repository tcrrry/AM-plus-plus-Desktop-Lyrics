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

The embedded Apple Music build is a split package. Run
`scripts/package-tcrrry-embedded.ps1` on the patched output directory to make
a single `.apks` file containing `base.apk` plus the matching ABI and density
splits. Recipients install it with a split-package installer such as SAI or
NPatch. A normal Android APK installer cannot install the `.apks` archive.
The current `arm64-xxxhdpi` archive is a device-specific build; a general
release needs the correct Apple Music splits for each supported device.

Keep the Tcrrry changes on the `tcrrry-lyrics` branch. For an upstream AM++
release, fetch its new commit, merge it into this branch, resolve conflicts,
run the project tests, and test settings and lyric injection on a phone. The
module must then be embedded into Apple Music again. For a new Apple Music
version, first follow `docs/apple-music-target-adaptation.md` to add and test
its exact hook profile; reusing a package built for 6.5.3 will not adapt it.
Use the same signing key for every embedded build intended to update an
existing installation. Different keys require uninstalling and lose host data.
