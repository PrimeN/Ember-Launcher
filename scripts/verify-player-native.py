#!/usr/bin/env python3
"""Audit generated AARs without packaging an APK. Python 3 and readelf required."""
import argparse, io, pathlib, re, struct, subprocess, tempfile, zipfile
p=argparse.ArgumentParser();p.add_argument('isolated_aar');p.add_argument('--nextlib');args=p.parse_args()
old={b'libavcodec.so',b'libavutil.so',b'libavformat.so',b'libavfilter.so',b'libavdevice.so',b'libswresample.so',b'libswscale.so',b'libmpv.so',b'libplayer.so'}
expected={'armeabi-v7a','arm64-v8a','x86','x86_64'}
with zipfile.ZipFile(args.isolated_aar) as z, tempfile.TemporaryDirectory() as td:
    abis=set();total=0;perabi={}
    for name in z.namelist():
        if not name.startswith('jni/') or not name.endswith('.so'):continue
        data=z.read(name);abi=name.split('/')[1];abis.add(abi);total+=len(data);perabi[abi]=perabi.get(abi,0)+len(data)
        assert not any(s+b'\0' in data for s in old),f'Unisolated library string: {name}'
        if name.endswith('libemplay.so'):
            assert b'[%s:%s] %s\0' not in data and b'[%s:%s] **\0' in data, f'Raw JNI logging remains: {name}'
        file=pathlib.Path(td)/name.replace('/','-');file.write_bytes(data)
        dynamic=subprocess.check_output(['readelf','-d',str(file)],text=True)
        needed=re.findall(r'\(NEEDED\).*\[(.*?)\]',dynamic)
        shipped={pathlib.PurePosixPath(x).name for x in z.namelist() if x.startswith('jni/'+abi+'/')}
        assert all(x in shipped or x in {'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so','libEGL.so','libGLESv2.so','libOpenSLES.so','libmediandk.so','libjnigraphics.so','libvulkan.so'} for x in needed),(name,needed)
        segments=subprocess.check_output(['readelf','-lW',str(file)],text=True)
        for row in segments.splitlines():
            if row.strip().startswith('LOAD') and abi in {'arm64-v8a','x86_64'}:
                assert int(row.split()[-1],16)>=16384, f'Not 16 KiB aligned: {name}'
        if name.endswith('libemcodec.so'):assert b'LIBAVCODEC_62\0' in data
        if name.endswith('libemfilter.so'):
            assert all(x in data for x in (b'bwdif\0',b'yadif\0',b'LIBAVFILTER_11\0')), f'Missing rebuilt filters: {name}'
    assert abis==expected,abis
    with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as jar:
        cls=jar.read('dev/jdtech/mpv/MPVLib.class')
        assert b'\x00\x03emv' in cls and b'\x00\x06emplay' in cls
    print('libmpv: all four ABIs, resolved DT_NEEDED, isolated loaders/SONAMEs, redacted JNI log text and 64-bit 16 KiB ELF alignment passed.')
    print('Uncompressed native MiB by ABI:',{a:round(n/1048576,2) for a,n in sorted(perabi.items())})
if args.nextlib:
    with zipfile.ZipFile(args.nextlib) as z:
        libs=[n for n in z.namelist() if n.endswith('/libavcodec.so')]
        assert libs and all(b'LIBAVCODEC_63\0' in z.read(n) for n in libs)
    print('Nextlib: its separate FFmpeg ABI (LIBAVCODEC_63) is retained.')
