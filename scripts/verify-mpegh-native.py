#!/usr/bin/env python3
"""Audit the two shipped MPEG-H libraries without executing Android binaries."""
import hashlib, pathlib, re, struct, subprocess

root = pathlib.Path(__file__).resolve().parent.parent
native = root / 'app/native/mpegh'
props = dict(line.split('=', 1) for line in (native/'checksums.properties').read_text().splitlines()
             if line and not line.startswith('#'))
assert hashlib.sha256((native/'source/mpeghdec-r3.0.2.tar.gz').read_bytes()).hexdigest() == props['source.sha256']
abis = {'arm64-v8a': 183, 'armeabi-v7a': 40}
files = list((root/'app/src/main/jniLibs').glob('*/libmpeghJNI.so'))
assert {f.parent.name for f in files} == set(abis), 'Only supported ARM libraries may ship'
expected = {'JNI_OnLoad'}
for source in (root/'app/src/main/java/androidx/media3/decoder/mpegh').glob('*Jni.java'):
    for name in re.findall(r'\bnative\s+\w+\s+(\w+)\(', source.read_text()):
        expected.add('Java_androidx_media3_decoder_mpegh_'+source.stem+'_'+name)
for file in files:
    abi = file.parent.name
    data = file.read_bytes()
    assert data[:4] == b'\x7fELF' and data[5] == 1
    assert struct.unpack_from('<H', data, 18)[0] == abis[abi]
    assert hashlib.sha256(data).hexdigest() == props[abi+'.sha256']
    dynamic = subprocess.check_output(['readelf', '-d', str(file)], text=True)
    needed = set(re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic))
    assert needed <= {'libandroid.so', 'liblog.so', 'libm.so', 'libdl.so', 'libc.so'}, needed
    assert 'Library soname: [libmpeghJNI.so]' in dynamic
    exports = set()
    for line in subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(file)], text=True).splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[6] != 'UND' and fields[4] in ('GLOBAL', 'WEAK'):
            exports.add(fields[7])
    assert expected <= exports, (abi, 'Missing JNI', expected-exports)
    assert exports == expected, (abi, 'Unexpected public native symbols', exports-expected)
    for line in subprocess.check_output(['readelf', '-lW', str(file)], text=True).splitlines():
        if line.strip().startswith('LOAD'):
            assert int(line.split()[-1], 16) >= 16384, (abi, line)
    print(f'{abi}: hash, ELF machine, JNI methods, system-only dependencies and 16 KiB alignment passed')
