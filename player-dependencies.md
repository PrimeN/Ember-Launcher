# Player dependencies and verification

JVM tests and native-file inspection do not establish hardware playback results.

## Playback architecture

- **A second internal engine:** Media3 and the TV decoder remain the normal route.
  Automatic mode can try libmpv for remaining container/decoder/output failures and
  selected styled ASS subtitles. Legacy WMV/ASF/RM/VOB-class files can start there.
  The same Ember controls, resume position and server reporting owner remain in use.
  Standard and Compatibility can also be selected explicitly. This broadens the
  playback routes without requiring a separate player app.
- **Sound recovery:** An encoded HDMI-output failure can try decoded PCM. Audio and
  video have independent, bounded recovery choices, reset for the next item. One
  bad file does not force software video on the following episode. Compatibility
  also has a bounded decoded-audio retry after a parsed-audio failure.
- **Safer HDR decisions:** DV7 or DV8 with explicit HDR10 metadata can use a compatible
  base-layer route. DV5 is not treated as ordinary HDR10 HEVC. This avoids an incorrect
  fallback; it does not create Dolby Vision support on an unsupported display.
- **Better captions:** Known subtitles appear before slower providers finish. Manual
  selections/Off survive part changes. Embedded and external timing/speed controls
  use the same meaning across engines. TTML and frame-rate-aware MicroDVD, bounded
  ZIP matching, UTF-16 text, authored styles, system caption style, native libass,
  manual SRT/VTT/ASS/TTML/SAMI and paired IDX+SUB or SUP files are included. Compatibility
  has a secondary subtitle track and its own delay, with an explicit private
  fontconfig file pointing to Android system fonts. No font files are copied or
  redistributed. Normal text files retain the
  Media3 overlay when that is the appropriate parser.
- **Files and folders:** Quick settings → Open media opens Android's file/USB picker
  or saved SMB3, WebDAV, SFTP, FTP and FTPS folders. Multi-file playback has queue
  editing, previous/next, repeat item/queue and a repeat range. Local files retain
  positions and track preferences under hashed identities. Local seek thumbnails
  use small, on-demand frames on Android 8.1+; Android 8.0 skips that preview.
- **Finding a NAS:** Open media → Network folders → Add network folder can find
  SMB servers advertised on the local network and browse authenticated disk shares.
  Discovery lasts at most 15 seconds, stops when the dialog closes, and never runs
  during launcher startup. Manual addresses remain available for servers without
  discovery or share-enumeration support. Credentials stay encrypted; enumeration
  uses the same signing/encryption policy as playback.
- **Scene bookmarks:** Playback options → Bookmarks saves named timestamps, with
  rename, remove and precise seek. Bookmarks follow the current activity/profile
  scope and are included in backups. Limits are 50 per title and 512 titles; reaching
  a limit does not delete saved bookmarks. Live channels and trailers omit this menu.
- **Decoder memory:** After stable Standard video playback, optional device-local
  hints remember a working decoder for that codec/resolution/HDR profile. Manual
  rules take priority. Hints expire after seven days and are invalidated by app,
  firmware or codec-module changes. Remembered software video shortcuts are limited
  to SDR up to 1080p. Settings → Player provides a reset; hints are not backed up.
- **Efficiency:** Normal playback keeps the hardware-first route. Indexed cue lookup
  avoids scanning every earlier subtitle every tick; parsing runs off the UI thread.
  Picture debanding, smoothing and higher-quality scalers are optional and off or
  inexpensive by default. The native demux cache is bounded (48 MiB forward/16 MiB back).
  A movie is streamed with byte ranges rather than downloaded as one large file.
- **Private transport:** Network accounts use verified Keystore-backed encryption,
  with no new plaintext fallback. SMB3 requests encryption/signing, SFTP requires a
  verified SHA256 host key, and FTPS uses system certificate/hostname verification.
  WebDAV blocks cross-origin credential redirects. Compatibility HTTP is relayed
  on loopback through OkHttp, verifies TLS, refuses HTTPS downgrades, strips private
  headers across origins and rewrites adaptive references/templates. The relay uses
  random tokens, bounded workers/headers, read-only methods and no disk body cache.
  Streams/calls/descriptors/temp subtitles close with the owning session. Remote
  protocol cleanup runs off the UI thread so a slow server cannot block closing
  the player. Phone setup
  is unrelated and is not enabled by opening a media file.

## Direct dependencies

All versions are pinned in `app/build.gradle.kts`.
No new API key, account, repository secret, NDK installation or manually supplied
native binary is required for an ordinary Gradle build.

| Dependency | Version | Purpose |
|---|---|---|
| AndroidX Media3 RTSP | 1.11.1 | RTSP source handling |
| AndroidX Media3 SmoothStreaming | 1.11.1 | SmoothStreaming manifests/segments |
| `dev.jdtech.mpv:libmpv` | 1.1.0 | Internal compatibility engine and native libass captions |
| `com.hierynomus:smbj` | 0.15.0 | Encrypted/signed SMB3 file access |
| `com.rapid7.client:dcerpc` | 0.12.13 | Authenticated SMB share enumeration |
| `commons-io:commons-io` / `org.apache.commons:commons-lang3` | 2.21.0 / 3.22.0 | RPC I/O and value helpers |
| `com.github.mwiede:jsch` | 2.28.8 | SFTP with a pinned host key |
| `commons-net:commons-net` | 3.13.0 | FTP and certificate-verified FTPS |

Existing Media3 core/UI/HLS/DASH stay at **1.11.1** and existing Nextlib stays at
**1.11.1-0.16.0**. Nextlib bundles AV1/dav1d. Media3 includes an AVI extractor; the compatibility
engine provides another route for variants the normal pipeline cannot handle.

SMBJ's runtime dependencies include SLF4J API 2.0.9, Bouncy Castle 1.79, mbassador
1.3.0 and asn-one 0.6.0. Commons IO is pinned to 2.21.0. SMBJ-RPC uses the existing SMBJ 0.15.0 transport; its older transitive dependencies are not imported. Gradle resolves these;
no logging backend that records private requests was added.

### Android SMB RPC adaptation and rebuild

`PrepareSmbRpc` in `app/build.gradle.kts` downloads the unmodified Maven Central
`com.rapid7.client:dcerpc:0.12.13` JAR, verifies SHA256
`ea3f970d4b6f5bedbca843551b9c4fe250de34942aa02e02a7f957a402f5cbb8`,
then rewrites constant-pool references from desktop-only
`java.rmi.UnmarshalException` and `java.rmi.MarshalException` to Ember's
`RpcParseException` (`IOException`).
Both constructor signatures, protocol instructions and upstream resource files
remain intact. This is an Ember modification, not an upstream Android release.
It does not suppress missing-class warnings or bundle a desktop RMI runtime.
The source for the exact dependency is available from
[SMBJ-RPC tag dcerpc-0.12.13](https://github.com/rapid7/smbj-rpc/tree/dcerpc-0.12.13)
and the matching Maven `-sources.jar`; its complete license is bundled in
`app/src/main/assets/licenses/SMBJ-RPC-BSD.txt` and shown in Legal.

An ordinary `gradle :app:assembleRelease` rebuilds the adapted JAR automatically
under `app/build/android-smb-rpc/`. No generated JAR is committed. Removing that
directory and rebuilding reproduces it from the pinned artifact. A unit test
exercises a malformed RPC response through the adapted exception path.

## Independent FFmpeg bundles must remain separate

Nextlib and libmpv 1.1.0 both use `LIBAVCODEC_63` / `LIBAVUTIL_61`, but ship
independently configured FFmpeg builds. Keep their private dependency namespaces
separate; matching major symbol versions do not make the bundles interchangeable. `IsolateMpvNative` instead produces a deterministic AAR in
`app/build/isolated-mpv/`, changing only same-length native library-name strings,
JNI loader constants, and the JNI verbose log format. Symbol ABI versions, offsets,
JNI class/method names and executable instructions are preserved.

- Upstream AAR SHA256:
  `20fd972e70d8e73bf8900d911f3ae0eeae354606baeb0fe58caa1a6380e196aa`
- Native library filenames/SONAME/DT_NEEDED strings get private `libem…` names.
- Java's `mpv` / `player` loader constants become `emv` / `emplay`.
- The JNI verbose log literal no longer emits arbitrary mpv message text, which can
  contain signed URLs. No raw log observer is registered. Diagnostics show format,
  engine, decoder and audio-output fields, never transport URLs or account fields.
- The task refuses an unexpected artifact hash, loader layout or log format. A future
  dependency upgrade requires reviewing those transformations again.
- `scripts/verify-player-native.py` checks loader names, dependencies, ABI versions,
  all four architectures, and **64-bit** 16 KiB ELF load-segment alignment. 32-bit
  libraries are packaged but are not claimed to have 16 KiB alignment.
- `app/proguard-rules.pro` preserves JNI observers/classes, reflective SFTP/crypto
  implementations and SMB event handlers for release shrinking. Optional desktop
  Kerberos/agent/logging/EL references are scoped out; SMB explicitly uses NTLM
  password authentication. An APK/device load remains part of later acceptance.

Example checks (Python 3 / `readelf` for the native audit):

```sh
gradle :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:mergeDebugNativeLibs
python3 scripts/verify-player-native.py app/build/isolated-mpv/libmpv-1.1.0.aar
```

These commands do not assemble an APK. Inspect lint's XML error severities too:
this repository's existing configuration does not abort automatically on lint errors.

## Source and license inventory

The Android wrapper is MIT; that does not describe the whole native stack. The
pinned build enables FFmpeg GPL/version3 and carries GPL obligations. License texts
are bundled in `app/src/main/assets/licenses/`, and Settings → Legal links this
inventory. When distributing binaries, provide the corresponding source, upstream
sources/build scripts and Ember's local transformations together; an MIT label on
the wrapper does not remove that requirement.

- [libmpv Android v1.1.0 source](https://github.com/jarnedemeulemeester/libmpv-android/tree/85a2445098b7cba99576c2963b1417b17b4debdd),
  including `buildscripts/include/depinfo.sh` and the native build scripts/patches.
  The source ref is the release tag, not the newer repository main branch.
- Release build inventory: mpv 0.42.0 (GPL), FFmpeg 9.0.2 (GPL/version3 enabled),
  libass 0.17.5 (ISC), dav1d 1.5.4 (BSD), libplacebo 7.387.0 (LGPL), mbedTLS 3.6.7
  (Apache), curl 8.23.0 (curl license), Lua 5.2.4 (MIT), FreeType 2.15.0 (FTL/GPL), fontconfig 2.18.3 (MIT),
  FriBidi 1.0.18 (LGPL), HarfBuzz 15.1.0 (MIT style), libunibreak 8.0 (zlib),
  libxml2 2.15.4 (MIT), and the Android NDK C++ runtime (Apache/LLVM exception).
  The upstream bundle uses NDK 30.0.16248370; Ember's custom components retain
  their pinned NDK 29.0.14206865 toolchain. Exact upstream patches/configuration
  are in that release's build scripts.
  The curl notice is bundled in `app/src/main/assets/licenses/curl-COPYING.txt`.
- [Nextlib source matching 1.11.1-0.19.0](https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.19.0), GPLv3:
  FFmpeg 9.0.1, dav1d 1.5.4, mbedTLS 3.6.7, libass 0.17.5, FreeType 2.14.1, FriBidi 1.0.16,
  HarfBuzz 14.4.0, Fontconfig 2.16.0, Expat 2.8.4 and libunibreak 7.0 (versions from its `ffmpeg/setup.sh`).
  The beta subtitle option uses the native subtitle renderer for authored ASS in Standard playback. Notices ship under `assets/native-dependencies/`.
- [Media3](https://github.com/androidx/media), Apache 2.0;
  [SMBJ](https://github.com/hierynomus/smbj), Apache 2.0;
  [JSch](https://github.com/mwiede/jsch), BSD style;
  [Commons Net](https://github.com/apache/commons-net), Apache 2.0.

## Limits that still require device acceptance

IntroDB provides optional read-only skip markers. VVC/H.266 routing uses the
bundled compatibility decoder. Support and output still require device testing.

Compilation, JVM fixtures and ELF inspection do not prove actual hardware decoding,
HDR/color accuracy, HDMI passthrough, surface recreation, D-pad behavior or projector
performance. Test the matrix on both capable and constrained TV devices, including
server multipart transitions, receiver changes, USB providers and real network shares.
There is no “all formats” guarantee: DRM/provider licenses, unsupported Dolby Vision
layers, HDMI limits and CPU/GPU capacity remain real boundaries.

Standard playback integrates Media3's matching 1.11.1 Java/JNI extension and
Fraunhofer r3.0.2 for ARM64/ARM32 only. No new Maven artifact is required. The
complete unmodified Fraunhofer source archive, full license, native checksums,
rebuild steps and wrapper changes are recorded in
[the MPEG-H native directory](app/native/mpegh/README.md). The Standard player
can decode supported unencrypted MPEG-H to stereo PCM; Compatibility/libmpv does
not use this extension. Software preference changes renderer priority instead of
forcing Nextlib's FFmpeg-only gate, so it retains MPEG-H and device-only AC-4
fallbacks. TV decoders only continues to exclude software extensions. AC-4 still
requires device support; no software AC-4 or IAMF decoder is bundled.

Software HDR
preservation/tone mapping is not advertised as equivalent to the TV's HDR pipeline.
Ember now replaces the pinned libmpv filter library with a selective FFmpeg 9.0.2
build containing BWDIF/YADIF, scale/format, audio filters (including `acompressor` for
Night listening) and the existing buffer filters. Both packaged ARM
ABIs retain private SONAMEs, symbol compatibility and 16 KiB alignment. Source,
checksums and rebuild/verification steps are in
[the native filter directory](app/native/ffmpeg-filters/README.md). Compatibility
playback exposes Off (default), Automatic and Always deinterlacing. Automatic uses
frame flags; the decoder-copy path makes CPU filter inputs readable. Progressive
frames bypass the automatic filter, although enabled copying can still add cost.
Host tests cover field order/frame counts and combing; real HDR/interlaced and
device performance acceptance is still required.
Playback uses foreground video and picture-in-picture.
