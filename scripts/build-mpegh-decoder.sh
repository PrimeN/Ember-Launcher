#!/usr/bin/env bash
# Build the bundled Fraunhofer source with Media3's JNI wrapper for supported TV CPUs.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_path="${1:?Usage: build-mpegh-decoder.sh NDK_DIRECTORY CMAKE_DIRECTORY [BUILD_DIRECTORY]}"
cmake_path="${2:?Supply Android SDK cmake/3.22.1}"
build_path="${3:-$(mktemp -d)}"
ndk_path="$(cd -- "$ndk_path" && pwd)"
cmake_path="$(cd -- "$cmake_path" && pwd)"
mkdir -p "$build_path"
build_path="$(cd -- "$build_path" && pwd)"
export SOURCE_DATE_EPOCH=1770000000
python3 - "$task_root" "$ndk_path" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1])
assert 'Pkg.Revision = 29.0.14206865' in pathlib.Path(sys.argv[2],'source.properties').read_text()
source=root/'app/native/mpegh/source/mpeghdec-r3.0.2.tar.gz'
assert hashlib.sha256(source.read_bytes()).hexdigest()=='21fb81510da91b8218ecb82e83a1e9cef3a3a08ba744f54ee48c42e22a5359e0'
PY
mkdir -p "$build_path/src/libmpegh"
cp "$task_root"/app/native/mpegh/jni/* "$build_path/src/"
tar -xzf "$task_root/app/native/mpegh/source/mpeghdec-r3.0.2.tar.gz" --strip-components=1 -C "$build_path/src/libmpegh"
for abi in arm64-v8a armeabi-v7a; do
    "$cmake_path/bin/cmake" -S "$build_path/src" -B "$build_path/$abi" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$cmake_path/bin/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_path/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_CXX_VISIBILITY_PRESET=hidden \
        -Dmpeghdec_BUILD_BINARIES=OFF -Dmpeghdec_BUILD_DOC=OFF -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$task_root=/ember -ffile-prefix-map=$build_path=/build" \
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL'
    "$cmake_path/bin/cmake" --build "$build_path/$abi" --target mpeghJNI -j "${MPEGH_BUILD_JOBS:-4}"
    mkdir -p "$task_root/app/src/main/jniLibs/$abi"
    cp "$build_path/$abi/libmpeghJNI.so" "$task_root/app/src/main/jniLibs/$abi/libmpeghJNI.so"
    "$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$task_root/app/src/main/jniLibs/$abi/libmpeghJNI.so"
    chmod 644 "$task_root/app/src/main/jniLibs/$abi/libmpeghJNI.so"
done
python3 - "$task_root" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1])
lines=['# Fraunhofer r3.0.2, Media3 1.11.1 JNI; ARM only; scripts/build-mpegh-decoder.sh',
       'source.commit=a315faa241dddcef7f849d288e89b810b5a81619',
       'source.sha256=21fb81510da91b8218ecb82e83a1e9cef3a3a08ba744f54ee48c42e22a5359e0',
       'ndk.version=29.0.14206865', 'media3.version=1.11.1']
for abi in ['arm64-v8a','armeabi-v7a']:
    data=(root/'app/src/main/jniLibs'/abi/'libmpeghJNI.so').read_bytes()
    lines.append(abi+'.sha256='+hashlib.sha256(data).hexdigest())
(root/'app/native/mpegh/checksums.properties').write_text('\n'.join(lines)+'\n')
PY
