# Ember libavfilter override

These two ARM libraries replace only `libavfilter.so` in the pinned libmpv 1.1.0
artifact before Ember's existing ELF/JNI namespace isolation runs. Codec, demux,
scaling and utility libraries remain those of the pinned artifact. In particular,
the two player engines retain their separate FFmpeg ABI namespaces.

The upstream filter library registered only the buffer source/sink filters. This
build adds BWDIF, YADIF, scale and format filters, keeping that public API and its
`LIBAVFILTER_12` symbol version. Automatic mpv deinterlacing can now instantiate
BWDIF for flagged interlaced frames. Off remains the default in Ember.

The shipped ARM builds also enable equalizer, pan, volume, aformat and aresample
for optional decoded-audio effects. Boosts reserve headroom and audio channel
mapping retains channel order. The filters link the existing libswresample ABI;
the locally built resampler is not substituted. Passthrough remains untouched.

Source: FFmpeg n9.0.2, commit
`946fcce07b6dcd0331c8cc609192aeff5e1924f8`.

- Source archive: <https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/946fcce07b6dcd0331c8cc609192aeff5e1924f8>
- Compiler: Android NDK 29.0.14206865, API 26 targets. Current APKs ship only arm64-v8a and armeabi-v7a.
- ARM builds retain their applicable architecture assembly.
- Licence: built with `--enable-gpl --enable-version3`; GNU GPL v3.
  The full licence is bundled in `app/src/main/assets/licenses/player-GPL-3.0.txt`.
  Source files and their original notices are available in the pinned archive.
- BWDIF notices include Thomas Mundt (2016), Michael Niedermayer (2006–2011),
  and the British Broadcasting Corporation (2012). YADIF includes Michael
  Niedermayer (2006–2011). Other FFmpeg source notices remain unchanged.
- No original source file is modified; this is a different, selective build
  configuration. Only the stripped filter libraries are shipped. The locally
  built utility/scaling libraries are not substituted into the player.
- SHA-256 digests are in `checksums.properties` and checked during every AAR
  transformation. New dependencies resolve to the existing utility/scaling/resampling ABI.

## Rebuild on Linux

Install make, a host C compiler and the pinned Android NDK, then run from the
repository root:

```sh
scripts/build-native-filters.sh /path/to/android-ndk-29 /path/to/build-directory
```

The script downloads the exact source commit and rebuilds the two supported ARM
libraries and the checksum index. Only arm64-v8a and armeabi-v7a are packaged. The script does not install SDK packages or accept licence prompts.
For the same-source host field-handling and audio checks, set `NATIVE_FILTER_HOST_CHECK=1`.

## Verify

```sh
python3 scripts/verify-native-filters.py /path/to/upstream-libmpv-1.1.0.aar
python3 scripts/verify-player-native.py app/build/isolated-mpv/libmpv-1.1.0.aar
python3 scripts/test-deinterlacing.py /path/to/host-ffmpeg-9.0.2
python3 scripts/test-audio-filters.py /path/to/host-ffmpeg-9.0.2
```

Checks cover hashes, ELF machines, public API compatibility, dependency symbols,
symbol versions, filter names, private loader names and 16 KiB alignment. The host
test verifies byte-identical progressive bypass, top/bottom-field frame doubling
and reduced combing on a synthetic moving edge. Actual Android device rendering,
hardware-frame copies, HDR/interlaced combinations and CPU budgets still need
device acceptance.

The builds also enable `acompressor` for optional night-listening compression.
The upstream compressor source is `libavfilter/af_sidechaincompress.c`, including
its original copyright notice for Paul B Mahol (2013). The pinned source archive
retains the complete original notices. Channel-linked peak detection and reserved
makeup headroom keep the sound image stable and reduce clipping; passthrough is
unchanged. Same-source host checks exercise the quiet/loud dynamic range too.
