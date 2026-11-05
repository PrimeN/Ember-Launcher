#!/usr/bin/env python3
"""Check filter overrides against the pinned upstream native libraries, without running Android ELF files."""
import argparse, hashlib, pathlib, re, struct, subprocess, tempfile, zipfile

p = argparse.ArgumentParser()
p.add_argument('upstream_aar')
p.add_argument('--filters', default='app/native/ffmpeg-filters')
a = p.parse_args()
root = pathlib.Path(a.filters)
properties = dict(line.split('=', 1) for line in (root/'checksums.properties').read_text().splitlines() if line and not line.startswith('#'))
machines = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86': 3, 'x86_64': 62}

def symbols(file):
    exported, imported = set(), set()
    for line in subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(file)], text=True).splitlines():
        parts = line.split()
        if len(parts) >= 8 and parts[0].endswith(':'):
            name = parts[7].replace('@@', '@')
            (imported if parts[6] == 'UND' else exported).add(name)
    return exported, imported

with zipfile.ZipFile(a.upstream_aar) as z, tempfile.TemporaryDirectory() as tmp:
    for abi, machine in machines.items():
        file = root/abi/'libavfilter.so'
        data = file.read_bytes()
        assert data[:4] == b'\x7fELF' and data[5] == 1
        assert struct.unpack_from('<H', data, 18)[0] == machine, abi
        assert hashlib.sha256(data).hexdigest() == properties[abi+'.sha256'], abi
        assert all(x in data for x in (b'bwdif\0', b'yadif\0', b'LIBAVFILTER_11\0')), abi
        deps = subprocess.check_output(['readelf', '-d', str(file)], text=True)
        assert 'Library soname: [libavfilter.so]' in deps
        needed = re.findall(r'\(NEEDED\).*\[(.*?)\]', deps)
        assert set(needed) <= {'libavutil.so', 'libswscale.so', 'libc.so', 'libm.so'}, (abi, needed)
        for line in subprocess.check_output(['readelf', '-lW', str(file)], text=True).splitlines():
            if line.strip().startswith('LOAD'):
                assert int(line.split()[-1], 16) >= 16384, (abi, line)
        upstream_exports = set()
        for name in ['libavfilter.so', 'libavutil.so', 'libswscale.so']:
            old = pathlib.Path(tmp)/(abi+'-'+name)
            old.write_bytes(z.read('jni/'+abi+'/'+name))
            exports, _ = symbols(old)
            if name == 'libavfilter.so': upstream_filter_exports = exports
            else: upstream_exports |= exports
        exports, imports = symbols(file)
        assert upstream_filter_exports <= exports, (abi, 'Missing filter API', upstream_filter_exports-exports)
        linked = {name for name in imports if name.startswith(('av_', 'sws_'))}
        assert linked <= upstream_exports, (abi, 'Missing dependency symbol', linked-upstream_exports)
        print(abi+': hash, machine, filters, symbol versions/dependencies, public API and 16 KiB alignment passed')
