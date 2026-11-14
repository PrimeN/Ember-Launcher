# Ember libavfilter override

These four libraries replace only `libavfilter.so` in the pinned libmpv 1.0.0
artifact before Ember's existing ELF/JNI namespace isolation runs. Codec, demux,
scaling and utility libraries remain those of the pinned artifact. In particular,
the two player engines retain their separate FFmpeg ABI namespaces.

The upstream filter library registered only the buffer source/sink filters. This
build adds BWDIF, YADIF, scale and format filters, keeping that public API and its
`LIBAVFILTER_11` symbol version. Automatic mpv deinterlacing can now instantiate
BWDIF for flagged interlaced frames. Off remains the default in Ember.

The shipped ARM builds also enable equalizer, pan, volume, aformat and aresample
for optional decoded-audio effects. Boosts reserve headroom and audio channel
mapping retains channel order. The filters link the existing libswresample ABI;
the locally built resampler is not substituted. Passthrough remains untouched.

Source: FFmpeg n8.1, commit
`9047fa1b084f76b1b4d065af2d743df1b40dfb56`.

- Source archive: <https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/9047fa1b084f76b1b4d065af2d743df1b40dfb56>
- Compiler: Android NDK 29.0.14206865, API 26 targets. Current APKs ship only arm64-v8a and armeabi-v7a.
- Assembly: NASM 2.16.01 for x86_64; x86 follows upstream's no-assembly choice;
  ARM builds retain their applicable architecture assembly.
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

Install make, a host C compiler, NASM and the pinned Android NDK, then run from the
repository root:

```sh
scripts/build-native-filters.sh /path/to/android-ndk-29 /path/to/build-directory
```

The script downloads the exact source commit and rebuilds the two supported ARM
libraries and the checksum index. To also rebuild the unused x86 targets,
set `NATIVE_FILTER_ABIS="x86 x86_64"`; NASM is required only for x86_64. It does not install SDK packages or accept licence prompts.
For the same-source host field-handling and audio checks, set `NATIVE_FILTER_HOST_CHECK=1`.

## Verify

```sh
python3 scripts/verify-native-filters.py /path/to/upstream-libmpv-1.0.0.aar
python3 scripts/verify-player-native.py app/build/isolated-mpv/libmpv-1.0.0.aar
python3 scripts/test-deinterlacing.py /path/to/host-ffmpeg-8.1
python3 scripts/test-audio-filters.py /path/to/host-ffmpeg-8.1
```

Checks cover hashes, ELF machines, public API compatibility, dependency symbols,
symbol versions, filter names, private loader names and 16 KiB alignment. The host
test verifies byte-identical progressive bypass, top/bottom-field frame doubling
and reduced combing on a synthetic moving edge. Actual Android device rendering,
hardware-frame copies, HDR/interlaced combinations and CPU budgets still need
device acceptance.
