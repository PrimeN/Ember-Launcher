# MPEG-H software audio decoder

Ember's Standard player includes the AndroidX Media3 MPEG-H extension and
Fraunhofer decoder r3.0.2. This adds actual decoding for supported, unencrypted
`mhm1`/`mha1` audio; naming a format alone was not sufficient. Fraunhofer documents
Baseline Profile, Level 4 for this version. The Media3 adapter selects CICP 2 and
outputs stereo, 16-bit PCM. This does not add immersive MPEG-H HDMI passthrough.

Device decoding remains first in the default **Software when needed** mode.
**Prefer software** changes renderer priority without disabling other decoders;
**TV decoders only** excludes the extension. Explicit runtime decoder recovery
still uses Nextlib's controller. Compatibility/libmpv does not use this decoder.

## Source and licenses

- Fraunhofer r3.0.2 commit:
  `a315faa241dddcef7f849d288e89b810b5a81619`.
  The complete, unmodified source is included in
  [`source/mpeghdec-r3.0.2.tar.gz`](source/mpeghdec-r3.0.2.tar.gz), with the original
  file notices and `LICENSE.txt`. It is not copied into the APK's assets.
  [Original source](https://github.com/Fraunhofer-IIS/mpeghdec/tree/a315faa241dddcef7f849d288e89b810b5a81619).
- Media3 1.11.1 commit:
  `8c6678b657ede1e7883fc164ef73ed483c7796c3`.
  The Java adapter is in `app/src/main/java/androidx/media3/decoder/mpegh` and
  its C++ wrapper is in `jni/`. These originate from
  [Media3's decoder_mpegh module](https://github.com/androidx/media/tree/8c6678b657ede1e7883fc164ef73ed483c7796c3/libraries/decoder_mpegh).
  Apache 2.0 headers are retained; the full Apache license is already bundled.
- Ember's changes to the Apache-licensed wrapper, dated 2026-11-03: reject DRM
  samples, validate JNI configuration/input/output ranges, free failed decoder
  initialization and safely handle repeated close/closed handles. The Fraunhofer
  decoder sources themselves are unchanged.
- The complete Fraunhofer license is bundled unchanged in
  `app/src/main/assets/licenses/Fraunhofer-FDK-MPEG-H.txt` and shown offline under
  Settings > Legal > Open source > Playback > Full license text. This includes
  its copyright permission, source-availability conditions and separate patent
  clause; publishing an app for free does not change those terms.
- License SHA-256:
  `e1453531ecba4e64a36693eaee51d76378b4ced5a2e1feb0dc427692d0cd9d47`.
- The complete NDK 29 LLVM/C++ runtime notice is also bundled unchanged as
  `app/src/main/assets/licenses/Android-NDK-29-LLVM-NOTICE.txt`, including the
  Apache 2.0 LLVM exceptions and libc++/libc++abi notices.

## Rebuild and native audit

Only **arm64-v8a and armeabi-v7a** are built, matching the app's APK filters.
Build dependencies are Android NDK 29.0.14206865, SDK CMake 3.22.1/Ninja, and Python.
The source archive is checked before extraction; no source or dependency download
is needed during rebuilding. Demo programs and their optional dependencies are
disabled. The decoder and C++ runtime are static inside `libmpeghJNI.so`, with
hidden helper symbols and only system shared-library dependencies. Existing
FFmpeg libraries are neither replaced nor linked into this decoder.

```sh
scripts/build-mpegh-decoder.sh /path/to/ndk/29.0.14206865 /path/to/cmake/3.22.1 /tmp/mpegh-build
python3 scripts/verify-mpegh-native.py
```

Gradle verifies both native checksums, their ELF machine types, the ARM-only ABI
set and the complete source archive's hash before building. The separate audit
also verifies every JNI export, absence of public helper symbols, system-only
dependencies and 16 KiB ELF alignment. R8 rules preserve the reflected renderer
and classes/fields accessed by JNI.

## Functional check

The host check compiles the same decoder and C++ JNI wrappers for Linux and uses
the shipped JNI declarations. Android logging is stubbed; the actual decoder and
JNI buffer operations run. It uses ffprobe only to locate input MP4 packets.
Requirements: Python 3.12+ (safe archive extraction), a Linux C++ compiler, JDK 17,
CMake/Ninja and ffprobe.

```sh
source /path/to/your-jdk-environment
python3 scripts/test-mpegh-decoder.py /path/to/cmake/3.22.1 /path/to/sample_mhm1_prefaudiolang.mp4
```

The fixture is available from the pinned Media3 repository at
`libraries/test_data/src/test/assets/media/mp4/sample_mhm1_prefaudiolang.mp4`.
It is not included in Ember's APK. The check requires nonzero stereo PCM on two
passes with equal output lengths after flushing, and checks JNI invalid ranges,
repeated close and calls after close. It does not establish Android AudioTrack
output, DRM, all MPEG-H profiles, seamless adaptation, HDMI or device CPU budgets.

AC-4 continues to use Media3's device-decoder path when available. This addition
does not bundle a software AC-4 or IAMF decoder.
