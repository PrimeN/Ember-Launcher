#!/usr/bin/env python3
"""Exercise channel mapping and equalization with the pinned FFmpeg host build."""
import argparse, array, math, pathlib, subprocess, tempfile
p=argparse.ArgumentParser()
p.add_argument('ffmpeg')
a=p.parse_args()
with tempfile.TemporaryDirectory() as tmp:
    root=pathlib.Path(tmp)
    def process(samples,channels,filters):
        source=root/'input.raw';target=root/'output.raw'
        source.write_bytes(array.array('h',samples).tobytes())
        command=[a.ffmpeg,'-hide_banner','-loglevel','error','-y','-f','s16le','-ar','48000','-ac',str(channels),'-i',str(source)]
        if filters:command+=['-af',filters]
        subprocess.run(command+['-c:a','pcm_s16le','-f','s16le',str(target)],check=True)
        output=array.array('h');output.frombytes(target.read_bytes());return output
    samples=[1000,-2000,3000,-4000]*200
    assert list(process(samples,2,''))==samples
    expected=[-2000,1000,-4000,3000]*200
    assert list(process(samples,2,'pan=2c|c0=c1|c1=c0'))==expected
    # A 5.1 stream must retain its centre, LFE and surround samples during a front-channel swap.
    surround=[1000,2000,3000,4000,5000,6000]*200
    assert list(process(surround,6,'pan=6c|c0=c1|c1=c0|c2=c2|c3=c3|c4=c4|c5=c5'))==[2000,1000,3000,4000,5000,6000]*200
    mono=process([3000,3000]*1000,2,'aformat=channel_layouts=mono')
    assert len(mono)==1000 and all(0<x<6000 for x in mono)
    def level(frequency,filters):
        sine=[round(6000*math.sin(2*math.pi*frequency*i/48000)) for i in range(24000)]
        out=process(sine,1,filters)[12000:]
        assert all(abs(x)<32767 for x in out)
        return math.sqrt(sum(x*x for x in out)/len(out))
    filters='volume=-6dB,equalizer=f=80:t=o:w=1:g=6'
    assert level(80,filters)>level(4000,filters)*1.5
print('Audio filters: flat bypass, stereo/surround mapping, mono downmix, bass response and headroom passed')
