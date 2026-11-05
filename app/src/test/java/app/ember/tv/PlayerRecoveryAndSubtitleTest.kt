package app.ember.tv

import app.ember.tv.data.SubtitleFiles
import app.ember.tv.player.CueIndex
import app.ember.tv.player.DecoderRecovery
import app.ember.tv.player.DolbyVisionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlayerRecoveryAndSubtitleTest {
    private fun zip(vararg entries:Pair<String,String>):ByteArray {
        val out=ByteArrayOutputStream();ZipOutputStream(out).use { z->entries.forEach { (n,t)->z.putNextEntry(ZipEntry(n));z.write(t.toByteArray());z.closeEntry() } };return out.toByteArray()
    }
    @Test fun automaticDecoderRecoveryIsIndependentAndResetsPerItem() {
        val r=DecoderRecovery()
        assertEquals("software",r.next(false,true));assertEquals("software",r.next(true,true))
        assertEquals("ffmpeg",r.next(false,true));assertNull(r.next(false,true))
        assertTrue(r.pcmFallback());assertFalse(r.pcmFallback());r.reset()
        assertFalse(r.pcm);assertEquals("software",r.next(false,true))
    }
    @Test fun disabledFfmpegAndUnsafeSoftwareAreNotAutomaticFallbacks() {
        val r=DecoderRecovery();assertEquals("software",r.next(true,false));assertNull(r.next(true,false))
        assertNull(r.next(false,true,false))
    }
    @Test fun dolbyVisionFallbackRequiresCompatibleProfileAndMetadata() {
        assertFalse(DolbyVisionPolicy.hdr10Base("dvhe.05.06",true))
        assertTrue(DolbyVisionPolicy.hdr10Base("dvhe.07.06",false))
        assertFalse(DolbyVisionPolicy.hdr10Base("dvh1.08.06",false))
        assertTrue(DolbyVisionPolicy.hdr10Base("dvh1.08.06",true))
        assertFalse(DolbyVisionPolicy.hdr10Base(null,true))
        assertFalse(DolbyVisionPolicy.hdr10Base("avc1.640028",true))
    }
    @Test fun ttmlAndMicroDvdAreNotRoutedToSrt() {
        assertEquals("ttml",SubtitleFiles.format("x.xml","<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>".toByteArray()))
        assertEquals("microdvd",SubtitleFiles.format("x.sub","{25}{50}Hello".toByteArray()))
        assertEquals("bitmap",SubtitleFiles.format("x.sub",byteArrayOf(0,0,1,0xba.toByte())))
        assertEquals("unknown",SubtitleFiles.format("x.sub","not a subtitle".toByteArray()))
    }
    @Test fun microDvdUsesFileFpsOrActualVideoFps() {
        assertTrue(SubtitleFiles.microDvd("{1}{1}25.0\n{25}{50}Hello|World",null)!!.contains("00:00:01,000 --> 00:00:02,000\nHello\nWorld"))
        assertTrue(SubtitleFiles.microDvd("{24}{48}Hi",24f)!!.contains("00:00:01,000"))
        assertNull(SubtitleFiles.microDvd("{24}{48}Hi",null))
    }
    @Test fun archiveMatchesEpisodeInsteadOfLargestFile() {
        val data=zip("Show.S01E01.en.srt" to "One","Show.S01E02.en.srt" to "Two".repeat(100))
        assertEquals("Show.S01E01.en.srt",SubtitleFiles.archive(data,SubtitleFiles.Target(season=1,episode=1))!!.first)
        assertNull(SubtitleFiles.archive(data,SubtitleFiles.Target(season=1,episode=9)))
    }
    @Test fun archiveMatchesReleaseAndLanguageDeterministically() {
        val data=zip("Show.1x01.fr.srt" to "French","Show.1x01.en.srt" to "English")
        assertEquals("Show.1x01.fr.srt",SubtitleFiles.archive(data,SubtitleFiles.Target(release="Show.1x01",language="fr"))!!.first)
    }
    @Test fun boundedReadsRejectOverflowAndPermitExactLimit() {
        assertEquals(4,SubtitleFiles.read(ByteArrayInputStream(ByteArray(4)),4).size)
        assertTrue(runCatching { SubtitleFiles.read(ByteArrayInputStream(ByteArray(5)),4) }.isFailure)
    }
    @Test fun archivesHaveEntryAndExpansionLimits() {
        assertTrue(runCatching { SubtitleFiles.archive(zip(*(0..SubtitleFiles.MAX_ENTRIES).map { "$it.srt" to "a" }.toTypedArray())) }.isFailure)
        assertTrue(runCatching { SubtitleFiles.archive(zip("large.srt" to "a".repeat(SubtitleFiles.MAX_DOWNLOAD+1))) }.isFailure)
    }
    @Test fun unicodeBomIsDecodedBeforeFormatDetection() {
        val bytes=byteArrayOf(0xff.toByte(),0xfe.toByte())+"<tt/>".toByteArray(Charsets.UTF_16LE)
        assertEquals("<tt/>",SubtitleFiles.bom(bytes))
    }
    @Test fun cueIndexPreservesOverlapsAndBackwardSeek() {
        data class Interval(val start:Long,val end:Long,val id:Int)
        val random=java.util.Random(123)
        val values=(0..999).map { val start=random.nextInt(10000).toLong();Interval(start,start+random.nextInt(1000)+1,it) }
        val index=CueIndex(values,{it.start},{it.end})
        for(time in listOf(0L,1000L,5000L,9999L,500L,4000L,15000L,-1L)) assertEquals(values.filter { time>=it.start&&time<it.end },index.at(time))
    }
}
