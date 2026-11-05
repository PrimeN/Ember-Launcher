#!/usr/bin/env bash
# Rebuild only libavfilter for the pinned libmpv FFmpeg ABI. No SDK licence prompts are accepted here.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_path="${1:?Usage: build-native-filters.sh NDK_DIRECTORY [BUILD_DIRECTORY]}"
build_path="${2:-$(mktemp -d)}"
ndk_path="$(cd -- "$ndk_path" && pwd)"
mkdir -p "$build_path"
build_path="$(cd -- "$build_path" && pwd)"
source_commit=9047fa1b084f76b1b4d065af2d743df1b40dfb56
ndk_version=29.0.14206865
python3 - "$ndk_path/source.properties" "$ndk_version" <<'PY'
import pathlib,sys
assert ('Pkg.Revision = '+sys.argv[2]) in pathlib.Path(sys.argv[1]).read_text(), 'Use the pinned NDK version'
PY
command -v nasm >/dev/null # Required for the original x86_64 assembly path; x86 follows upstream's no-asm build.
export PATH="$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export SOURCE_DATE_EPOCH=1770000000
mkdir -p "$build_path/source"
curl --fail --location --retry 2 "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/$source_commit" -o "$build_path/source.tar.gz"
tar -xzf "$build_path/source.tar.gz" --strip-components=1 -C "$build_path/source"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    extra=()
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android; cc=aarch64-linux-android26-clang; arch=aarch64; cpu=armv8-a ;;
        armeabi-v7a) triple=arm-linux-androideabi; cc=armv7a-linux-androideabi26-clang; arch=arm; cpu=armv7-a; extra=("--extra-cflags=-mfpu=neon -mcpu=cortex-a8") ;;
        x86) triple=i686-linux-android; cc=i686-linux-android26-clang; arch=x86; cpu=i686; extra=(--disable-asm) ;;
        x86_64) triple=x86_64-linux-android; cc=x86_64-linux-android26-clang; arch=x86_64; cpu=generic ;;
    esac
    mkdir -p "$build_path/$abi"
    (
        cd "$build_path/$abi"
        "$build_path/source/configure" --target-os=android --enable-cross-compile --cross-prefix="$triple-" \
            --cc="$cc" --arch="$arch" --cpu="$cpu" "${extra[@]}" \
            --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip --pkg-config=false \
            --disable-autodetect --disable-everything --disable-programs --disable-doc --disable-network \
            --disable-avcodec --disable-avformat --disable-avdevice --disable-swresample \
            --enable-avfilter --enable-swscale --enable-filter=bwdif,yadif,scale,format \
            --enable-gpl --enable-version3 --disable-static --enable-shared --disable-debug \
            --extra-ldflags=-Wl,-z,max-page-size=16384
        make -j"${NATIVE_FILTER_JOBS:-4}" libavfilter/libavfilter.so
    )
    mkdir -p "$task_root/app/native/ffmpeg-filters/$abi"
    cp "$build_path/$abi/libavfilter/libavfilter.so.11" "$task_root/app/native/ffmpeg-filters/$abi/libavfilter.so"
    llvm-strip --strip-unneeded "$task_root/app/native/ffmpeg-filters/$abi/libavfilter.so"
    chmod 644 "$task_root/app/native/ffmpeg-filters/$abi/libavfilter.so"
done
python3 - "$task_root/app/native/ffmpeg-filters" "$source_commit" "$ndk_version" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1])
lines=['# FFmpeg n8.1; libavfilter only; GPL v3; source/build steps in scripts/build-native-filters.sh',
       'source.commit='+sys.argv[2], 'ndk.version='+sys.argv[3], 'ffmpeg.version=8.1']
for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
    lines.append(abi+'.sha256='+hashlib.sha256((root/abi/'libavfilter.so').read_bytes()).hexdigest())
(root/'checksums.properties').write_text('\n'.join(lines)+'\n')
PY
if [[ "${NATIVE_FILTER_HOST_CHECK:-0}" == 1 ]]; then
    mkdir -p "$build_path/host-check"
    (
        cd "$build_path/host-check"
        "$build_path/source/configure" --cc=gcc --disable-autodetect --disable-everything --disable-doc --disable-network \
            --disable-avdevice --disable-swresample --enable-avcodec --enable-avformat --enable-avfilter --enable-swscale \
            --enable-filter=bwdif,yadif,scale,format,setfield --enable-decoder=rawvideo --enable-encoder=rawvideo \
            --enable-demuxer=rawvideo --enable-muxer=rawvideo --enable-protocol=file --enable-ffmpeg --disable-ffprobe \
            --enable-gpl --enable-version3 --disable-shared --enable-static --disable-debug
        make -j"${NATIVE_FILTER_JOBS:-4}" ffmpeg
    )
    python3 "$task_root/scripts/test-deinterlacing.py" "$build_path/host-check/ffmpeg"
fi
