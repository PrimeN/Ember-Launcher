package app.ember.tv.player

import app.ember.tv.mediaserver.Chapter

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class Mp4ChaptersTest {
    private fun int(n:Int)=ByteBuffer.allocate(4).putInt(n).array()
    private fun long(n:Long)=ByteBuffer.allocate(8).putLong(n).array()
    private fun box(name:String,vararg body:ByteArray):ByteArray {val b=body.fold(byteArrayOf()){a,v->a+v};return int(b.size+8)+name.toByteArray()+b}
    private fun source(file:ByteArray,reads:MutableList<Pair<Long,Int>>?=null):List<Chapter> = Mp4Chapters.read(file.copyOfRange(0,minOf(24,file.size))) {at,size->reads?.add(at to size);if(at>=file.size)null else file.copyOfRange(at.toInt(),minOf(file.size,at.toInt()+size))}
    @Test fun chapterListEntriesAreFoundAfterMediaWithoutReadingTheMediaPayload() {
        val entries=listOf(0L to "Intro",60000L to "Film").map {(time,name)->long(time*10000)+byteArrayOf(name.length.toByte())+name.toByteArray()}.fold(byteArrayOf()){a,b->a+b}
        val movie=box("moov",box("udta",box("chpl",byteArrayOf(1,0,0,0),int(0),byteArrayOf(2),entries)))
        val head=box("ftyp",byteArrayOf(0,0,0,0))+int(100000008)+"mdat".toByteArray()
        val reads=mutableListOf<Pair<Long,Int>>()
        val result=Mp4Chapters.read(head) {at,size->reads+=at to size;val offset=at-100000020L;if(offset>=0 && offset+size<=movie.size)movie.copyOfRange(offset.toInt(),offset.toInt()+size) else null}
        assertEquals(listOf(Chapter("Intro",0),Chapter("Film",60000)),result)
        assertTrue(reads.all {it.first>=100000020L});assertTrue(reads.sumOf {it.second}<1000)
    }
    @Test fun referencedChapterTrackUsesTextAndItsTimeScale() {
        val labels=listOf("Opening","Main feature").map {byteArrayOf(0,it.length.toByte())+it.toByteArray()}
        val ftyp=box("ftyp",int(0));val media=box("mdat",*labels.toTypedArray());val offset=ftyp.size+8
        val tables=box("stbl",
            box("stts",int(0),int(1),int(2),int(30000)),
            box("stsz",int(0),int(0),int(2),*labels.map {int(it.size)}.toTypedArray()),
            box("stsc",int(0),int(1),int(1),int(2),int(1)),
            box("stco",int(0),int(1),int(offset)))
        fun tkhd(id:Int)=box("tkhd",int(0),int(0),int(0),int(id))
        val video=box("trak",tkhd(1),box("tref",box("chap",int(2))))
        val text=box("trak",tkhd(2),box("mdia",box("mdhd",int(0),int(0),int(0),int(1000)),box("hdlr",int(0),int(0),"text".toByteArray()),box("minf",tables)))
        val reads=mutableListOf<Pair<Long,Int>>()
        assertEquals(listOf(Chapter("Opening",0),Chapter("Main feature",30000)),source(ftyp+media+box("moov",video,text),reads))
        assertEquals(1,reads.count {it.first>=offset&&it.first<offset+labels.sumOf(ByteArray::size)})
        assertTrue(reads.size<=4)
        // A text track without a chapter reference must not become a chapter list.
        assertTrue(source(ftyp+media+box("moov",text)).isEmpty())
    }
    @Test fun malformedLengthsAndTruncatedSamplesReturnNoChapters() {
        assertTrue(source(int(4)+"moov".toByteArray()).isEmpty())
        assertTrue(source(box("moov",box("udta",box("chpl",byteArrayOf(1,0,0,0),int(0),byteArrayOf(1))))).isEmpty())
    }
}
