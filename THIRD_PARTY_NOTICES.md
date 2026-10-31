# Third-party notices and distribution boundary

The application's own source remains **GPL-3.0-or-later**, as specified in the existing project README and `LICENSE`. Components below keep their own notices and terms; this document does not relicense them. No Seal or YTDLnis application source or branding is included.

## Materials included in this source repository

| Material | Exact source/version | License and attribution |
| --- | --- | --- |
| Official yt-dlp zipapp (`app/src/main/res/raw/ytdlp`) | [2026.10.26](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26), source commit `c91f8de0bfb3577894abad716eaaa01659e6bd14`; SHA-256 `e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3` | Unlicense; [included text](licenses/yt-dlp-Unlicense.txt). The zipapp contains Python source, not a native Python interpreter. |
| yt-dlp-ejs inside that zipapp | [0.8.0](https://github.com/yt-dlp/ejs/tree/0.8.0) | Unlicense; [included text](licenses/yt-dlp-ejs-Unlicense.txt). Generated JS embeds Meriyah 6.1.4 (ISC; KFlash and others) and Astring 1.9.0 (MIT; David Bonnet). Complete permission notices are retained inside the zipapp and in [this copy](licenses/yt-dlp-ejs-bundled-NOTICE.txt). |
| Experimental Douyin extractor override | [yt-dlp PR #17826](https://github.com/yt-dlp/yt-dlp/pull/17826), `728fe46fbaa97d6f03efcaf9e766d4db63338b8d`, author `viking1304` | Unlicense; provenance, wrapper changes and validation limits in [DOUYIN_PATCH](docs/DOUYIN_PATCH.md). |
| Big Buck Bunny test excerpt, portrait derivative and decoded frame | [Official movie](https://peach.blender.org/download/), [official license statement](https://peach.blender.org/about/) | CC BY 3.0. `(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org`. Excerpt/crop are declared derivatives, not original portrait footage. [Details](test-media/README.md). Test-only, not production APK assets. |

## Dependencies fetched by the build (not vendored here)

The complete resolved Java/Kotlin runtime set is listed in [RUNTIME_DEPENDENCIES](docs/RUNTIME_DEPENDENCIES.md). It covers **75 distinct artifact coordinates**, including transitive dependencies. POM declarations are evidence of the stated license, not proof of complete binary notice/source coverage.

| Family | Version(s) | Declared license / source |
| --- | --- | --- |
| youtubedl-android library/common/ffmpeg/aria2c wrappers | 0.18.1 | GPL v3; [fixed upstream tree](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760). Native contents require separate review. |
| AndroidX Media3 | 1.9.4 | Apache 2.0; [source](https://github.com/androidx/media/tree/1.9.4) |
| AndroidX Webkit and other AndroidX modules | Webkit 1.12.1; others in inventory | Apache 2.0; [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin / kotlinx-coroutines | stdlib 2.0.20; jdk7/jdk8 adapters 1.8.0; coroutines 1.9.0 | Apache 2.0; [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Guava and Google annotations/helpers | Guava 33.3.1-android; others in inventory | Apache 2.0 per POM; [Guava](https://github.com/google/guava) |
| Jackson | annotations/core/databind 2.11.1 | Apache 2.0; [Jackson](https://github.com/FasterXML/jackson) |
| Apache Commons | IO 2.5; Compress 1.12 | Apache 2.0; [IO](https://commons.apache.org/proper/commons-io/), [Compress](https://commons.apache.org/proper/commons-compress/) |
| Lottie / Okio | 6.6.0 / 1.17.6 | Apache 2.0; [Lottie](https://github.com/airbnb/lottie-android), [Okio](https://github.com/square/okio) |
| Checker Framework annotations | checker-qual 3.43.0 | MIT per POM; [source](https://github.com/typetools/checker-framework) |
| JetBrains annotations | 23.0.0 | Apache 2.0 |

Build/test-only dependencies include Gradle 8.9 (Apache 2.0), Android Gradle Plugin 8.7.3, JUnit 4.13.2 (EPL 1.0), JSON-java 20240303 (public domain) and Robolectric 4.16.1 (Apache 2.0). They are not an assertion about APK runtime coverage.

## Native audit — not yet cleared for public APK distribution

| Upstream AAR 0.18.1 | SHA-256 |
| --- | --- |
| library | `579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef` |
| common | `e0c0530bdbdf203e1598e900085d3582e27360731872872f3a67f23a9b24c0b5` |
| ffmpeg | `0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49` |
| aria2c | `1dc1fff42d18b42ba9a60a5688530d7f14dda8bf9450178b18ccd84a1bff4311` |

All eight top-level ARM64 native files/archives extracted from these AARs were byte-matched through Git blob hashes to upstream tag 0.18.1. **That establishes artifact identity, not complete Corresponding Source.**

Direct inspection found:

- FFmpeg: upstream update says 7.1.1; configuration includes `--enable-gpl --enable-version3`, and `libavutil` reports **GPL version 3 or later**. No `--enable-nonfree` flag was present. Its archive also includes x264, x265, Xvid, FFTW, Rubber Band, libass, libvpx, dav1d, AOM, rav1e, SVT-AV1, WebP and many other libraries.
- Python: binary identifies 3.12.11; upstream Python uses the PSF license and historical notices. Its archive also includes Readline/GDBM and extension libraries. Included Python packages identify Mutagen 1.47.0 (GPL-2.0-or-later in the retained header) and PyCryptodome 3.23.0 ([public domain/BSD-2-Clause](https://github.com/Legrandin/pycryptodome/blob/v3.23.0/LICENSE.rst)).
- QuickJS: binary identifies 2025-04-26; upstream uses MIT. Exact downstream build/source revision remains to be documented.
- aria2: 1.37.0, GPL-2.0-or-later; its archive includes GnuTLS, Nettle, GMP, libxml2, c-ares, libiconv, libunistring and others.

These observations are **not** a complete SBOM/license audit. Exact native source/build correspondence remains incomplete. The upstream [FFmpeg guide](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) and [Python guide](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md) do not pin the Termux package snapshot for these binaries. The [FFmpeg update](https://github.com/yausername/youtubedl-android/commit/f2280ae7d8bc8d59590c8727a89f047983867c10) replaces prebuilt binaries, not their complete source/build closure.

Before APK distribution, provide all required license/copyright notices and appropriate Corresponding Source and build material for every covered component. GPLv3 section 6(d) allows equivalent network source access with clear directions beside object code; a moving upstream repository or application Java files alone do not establish coverage of shipped native libraries. See [RELEASE_STATUS](docs/RELEASE_STATUS.md). Until resolved, this repository offers no public APK and makes no binary redistribution compliance claim.
