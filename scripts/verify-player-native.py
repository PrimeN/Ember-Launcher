#!/usr/bin/env python3
"""Audit isolated native AARs and optional APK dependency linkage. Python 3 and readelf required."""
import argparse, io, pathlib, re, struct, subprocess, sys, tempfile, zipfile
p=argparse.ArgumentParser();p.add_argument('isolated_aar');p.add_argument('--nextlib');p.add_argument('--apk');args=p.parse_args()
def check_linked_symbols(archive, prefix):
    """Resolve versioned FFmpeg and C++ imports through each ELF's own dependency tree."""
    with zipfile.ZipFile(archive) as z, tempfile.TemporaryDirectory() as tmp:
        libraries={}
        for entry in z.namelist():
            parts=entry.split('/')
            if len(parts)!=3 or parts[0]!=prefix or not entry.endswith('.so'):continue
            abi,name=parts[1:]
            file=pathlib.Path(tmp)/(abi+'-'+name);file.write_bytes(z.read(entry))
            dynamic=subprocess.check_output(['readelf','-d',str(file)],text=True)
            needed=re.findall(r'\(NEEDED\).*\[(.*?)\]',dynamic)
            exports,imports=set(),set()
            for line in subprocess.check_output(['readelf','--dyn-syms','--wide',str(file)],text=True).splitlines():
                fields=line.split()
                if len(fields)<8 or not fields[0].endswith(':'):continue
                symbol=fields[7].replace('@@','@')
                if fields[6]!='UND':
                    exports.add(symbol);exports.add(symbol.split('@')[0])
                elif fields[4]=='GLOBAL' and (symbol.startswith('_Z') or '@LIBAV' in symbol or '@LIBSW' in symbol):imports.add(symbol)
            if prefix == 'lib' and name == 'libass.so' and abi == 'arm64-v8a':
                segments = subprocess.check_output(['readelf', '-lW', str(file)], text=True)
                assert all(int(row.split()[-1], 16) >= 16384 for row in segments.splitlines()
                           if row.strip().startswith('LOAD')), (abi, name, '16 KiB alignment')
            libraries.setdefault(abi,{})[name]=(needed,exports,imports)
        assert set(libraries)=={'arm64-v8a','armeabi-v7a'},libraries.keys()
        for abi,items in libraries.items():
            if prefix == 'lib':
                assert ('libass.so' in items and 'ass_render_frame' in items['libass.so'][1]
                        and 'libass.so' in items['libmedia3ext.so'][0]), (abi, 'Nextlib ASS linkage')
                assert all('libass.so' not in deps for name, (deps, _, _) in items.items()
                           if name.startswith('libem')), (abi, 'mpv ASS isolation')
            for name,(_,_,imports) in items.items():
                seen=set();available=set();pending=[name]
                while pending:
                    child=pending.pop()
                    if child in seen or child not in items:continue
                    seen.add(child);deps,exports,_=items[child];available|=exports;pending.extend(deps)
                missing=imports-available
                assert not missing,(abi,name,'Unresolved FFmpeg/C++ imports',sorted(missing))
            print(abi+': versioned FFmpeg and C++ symbols resolve within packaged dependency trees')

old={b'libavcodec.so',b'libavutil.so',b'libavformat.so',b'libavfilter.so',b'libavdevice.so',b'libswresample.so',b'libswscale.so',b'libmpv.so',b'libplayer.so'}
expected={'armeabi-v7a','arm64-v8a'}
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
        if name.endswith('libemcodec.so'):assert b'LIBAVCODEC_63\0' in data
        if name.endswith('libemfilter.so'):
            assert all(x in data for x in (b'bwdif\0',b'yadif\0',b'LIBAVFILTER_12\0',b'acompressor\0')), f'Missing rebuilt filters: {name}'
    assert abis==expected,abis
    with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as jar:
        cls=jar.read('dev/jdtech/mpv/MPVLib.class')
        assert b'\x00\x03emv' in cls and b'\x00\x06emplay' in cls
    print('libmpv: both ARM ABIs, resolved DT_NEEDED, isolated loaders/SONAMEs, redacted JNI log text and 64-bit 16 KiB ELF alignment passed.')
    print('Uncompressed native MiB by ABI:',{a:round(n/1048576,2) for a,n in sorted(perabi.items())})
if args.nextlib:
    with zipfile.ZipFile(args.nextlib) as z:
        libs=[n for n in z.namelist() if n.endswith('/libavcodec.so')]
        assert libs and all(b'LIBAVCODEC_63\0' in z.read(n) for n in libs)
    print('Nextlib: its separate FFmpeg ABI (LIBAVCODEC_63) is retained.')

check_linked_symbols(args.isolated_aar,'jni')
if args.apk:
    check_linked_symbols(args.apk, 'lib')
    with zipfile.ZipFile(args.apk) as z:
        assert 'assets/native-dependencies/libass-0.17.5/COPYING' in z.namelist(), 'Missing libass notice'
