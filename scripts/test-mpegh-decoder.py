#!/usr/bin/env python3
"""Host JNI smoke test using the shipped C++ wrapper and unmodified decoder source.

Requires Linux C++ compiler, JDK, CMake/Ninja and ffprobe. Supply Media3 1.11.1's
sample_mhm1_prefaudiolang.mp4 (or another unencrypted MHAS MP4) as a fixture.
This tests decoding/JNI bounds, not Android AudioTrack, hardware, or HDMI output.
"""
import argparse, json, os, pathlib, re, struct, subprocess, tarfile, tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('cmake_directory')
p.add_argument('fixture')
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
cmake = pathlib.Path(a.cmake_directory).resolve()/'bin/cmake'
ninja = cmake.parent/'ninja'
jdk = pathlib.Path(os.environ['JAVA_HOME'])
fixture = pathlib.Path(a.fixture).resolve()
packets = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'a:0',
    '-show_packets', '-show_entries', 'packet=pts_time,pos,size', '-of', 'json', str(fixture)]))['packets']
assert packets, 'No audio packets in fixture'
with tempfile.TemporaryDirectory(prefix='ember-mpegh-host-') as tmp:
    work = pathlib.Path(tmp)
    # The checked archive contains a single pinned source tree. No network fetches.
    with tarfile.open(root/'app/native/mpegh/source/mpeghdec-r3.0.2.tar.gz') as tar:
        tar.extractall(work/'source', filter='data')
    decoder_source = next((work/'source').iterdir())
    (work/'stub/android').mkdir(parents=True)
    (work/'stub/android/log.h').write_text('''#pragma once
#include <stdint.h>
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_DEBUG 3
extern "C" int __android_log_print(int,const char*,const char*,...);
''')
    (work/'log.cpp').write_text('extern "C" int __android_log_print(int,const char*,const char*,...){return 0;}')
    jni = root/'app/native/mpegh/jni'
    (work/'CMakeLists.txt').write_text(f'''cmake_minimum_required(VERSION 3.21)
project(EmberMpeghHost CXX)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(mpeghdec_BUILD_BINARIES OFF CACHE BOOL "" FORCE)
set(mpeghdec_BUILD_DOC OFF CACHE BOOL "" FORCE)
add_subdirectory("{decoder_source}" decoder)
add_library(hostMpeghJNI SHARED "{jni}/mpegh_jni.cpp" "{jni}/mpeghuimanager_jni.cpp" log.cpp)
target_include_directories(hostMpeghJNI PRIVATE "{jdk}/include" "{jdk}/include/linux" stub "{decoder_source}/include")
target_link_libraries(hostMpeghJNI PRIVATE mpeghdec)
''')
    subprocess.run([str(cmake), '-S', str(work), '-B', str(work/'build'), '-G', 'Ninja',
        '-DCMAKE_MAKE_PROGRAM='+str(ninja), '-DCMAKE_BUILD_TYPE=Release'], check=True)
    subprocess.run([str(cmake), '--build', str(work/'build'), '--target', 'hostMpeghJNI', '-j', '4'], check=True)
    java = work/'java/androidx/media3/decoder/mpegh'
    java.mkdir(parents=True)
    for name in ['MpeghDecoderJni', 'MpeghUiManagerJni']:
        source = (root/f'app/src/main/java/androidx/media3/decoder/mpegh/{name}.java').read_text()
        # Annotations are not needed in this Android-free host harness.
        source = re.sub(r'^import androidx\..*;\n|^@UnstableApi\n', '', source, flags=re.M).replace('@Nullable ', '')
        (java/(name+'.java')).write_text(source)
    (java/'MpeghDecoderException.java').write_text('''package androidx.media3.decoder.mpegh;
public class MpeghDecoderException extends Exception {
 public MpeghDecoderException(String message) { super(message); }
}
''')
    (java/'HostCheck.java').write_text('''package androidx.media3.decoder.mpegh;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
public class HostCheck {
 interface Operation {void run() throws Exception;}
 static void rejects(Operation operation) throws Exception {
  try { operation.run(); } catch(MpeghDecoderException expected) { return; }
  throw new AssertionError("Invalid JNI range was accepted");
 }
 public static void main(String[] args) throws Exception {
  System.load(args[0]);
  MpeghDecoderJni invalid = new MpeghDecoderJni();
  rejects(() -> invalid.init(2,new byte[1],2)); invalid.destroy();
  MpeghDecoderJni decoder = new MpeghDecoderJni();
  decoder.init(2,new byte[0],0);
  rejects(() -> decoder.process(ByteBuffer.allocateDirect(1),2,0));
  rejects(() -> decoder.process(ByteBuffer.allocate(1),1,0));
  rejects(() -> decoder.getSamples(ByteBuffer.allocateDirect(8),-1));
  long previousBytes=-1;
  for(int pass=0;pass<2;pass++) {
   if(pass>0) decoder.flush();
   ByteBuffer output=ByteBuffer.allocateDirect(3072*24*6*2);
   long bytes=0, energy=0; int channels=0, sampleRate=0;
   try(DataInputStream in = new DataInputStream(Files.newInputStream(Path.of(args[1])))) {
    int count=in.readInt();
    for(int packet=0;packet<count;packet++) {
     long pts=in.readLong(); byte[] raw=in.readNBytes(in.readInt());
     ByteBuffer input=ByteBuffer.allocateDirect(raw.length); input.put(raw).flip();
     decoder.process(input,raw.length,pts);
     int written;
     while((written=decoder.getSamples(output,0))>0) {
      bytes+=written; channels=decoder.getNumChannels(); sampleRate=decoder.getSamplerate();
      for(int i=0;i<written;i+=2) energy+=Math.abs((int)output.getShort(i));
     }
    }
   }
   if(bytes<=0 || energy<=0 || channels!=2 || sampleRate<=0) throw new AssertionError("No valid stereo PCM");
   if(previousBytes>=0 && bytes!=previousBytes) throw new AssertionError("Flush/replay changed PCM length");
   previousBytes=bytes;
   System.out.println("pass "+pass+": decoded "+bytes+" PCM bytes, "+channels+" channels at "+sampleRate+" Hz; nonzero audio");
  }
  decoder.destroy(); decoder.destroy();
  rejects(() -> decoder.flush());
  if(decoder.getSamplerate()!=-1) throw new AssertionError("Closed decoder metadata");
  System.out.println("JNI input/config/output bounds, repeated close and closed-handle checks passed");
 }
}
''')
    stream = work/'packets.bin'
    raw = fixture.read_bytes()
    with stream.open('wb') as out:
        out.write(struct.pack('>i', len(packets)))
        for packet in packets:
            pos, size = int(packet['pos']), int(packet['size'])
            out.write(struct.pack('>qi', round(float(packet['pts_time'])*1_000_000), size))
            out.write(raw[pos:pos+size])
    classes = work/'classes'
    subprocess.run([str(jdk/'bin/javac'), '-d', str(classes), *map(str, java.glob('*.java'))], check=True)
    subprocess.run([str(jdk/'bin/java'), '-cp', str(classes), 'androidx.media3.decoder.mpegh.HostCheck',
        str(work/'build/libhostMpeghJNI.so'), str(stream)], check=True)
