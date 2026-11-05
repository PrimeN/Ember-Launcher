#!/usr/bin/env python3
"""Exercise FFmpeg's field handling using a host CLI built from the same source as the Android filter overrides."""
import argparse, pathlib, subprocess, tempfile

p = argparse.ArgumentParser()
p.add_argument('ffmpeg', help='Host FFmpeg executable; use the pinned n8.1 source for release acceptance')
a = p.parse_args()
width = height = 64
frames = 8
raw = bytearray()
for frame in range(frames):
    for y in range(height):
        left = 8 + ((frame*4 + (y % 2)*2) % 32)
        raw.extend(235 if left <= x < left+12 else 16 for x in range(width))
    raw.extend(bytes([128])*(width*height//2))

def comb(buf):
    return sum(abs(buf[y*width+x]-buf[(y-1)*width+x]) for y in range(1, height) for x in range(width))/(width*(height-1))

with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    (root/'fields.yuv').write_bytes(raw)
    for mode in ['prog', 'tff', 'bff']:
        target = root/(mode+'.yuv')
        subprocess.run([a.ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo', '-pixel_format', 'yuv420p',
                        '-video_size', '64x64', '-framerate', '25', '-i', str(root/'fields.yuv'),
                        '-vf', 'setfield='+mode+',bwdif=mode=send_field:parity=auto:deint=interlaced',
                        '-fps_mode', 'passthrough', '-c:v', 'rawvideo', '-f', 'rawvideo', str(target)], check=True)
        data = target.read_bytes()
        count = len(data)//(width*height*3//2)
        assert count == (frames if mode == 'prog' else frames*2), (mode, count)
        if mode == 'prog': assert data == raw, 'Progressive bypass changed pixels'
        else: assert comb(data) < comb(raw)*.5, (mode, comb(data), comb(raw))
        print(mode+': frame count, progressive bypass/interlaced field doubling and combing checks passed')
