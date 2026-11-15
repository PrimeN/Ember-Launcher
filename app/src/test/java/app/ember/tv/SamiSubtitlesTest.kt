package app.ember.tv

import app.ember.tv.data.SamiSubtitles
import app.ember.tv.data.SubtitleFiles
import org.junit.Assert.*
import org.junit.Test

class SamiSubtitlesTest {
    @Test fun blankSyncTerminatesCueAndPreservesTheGap() {
        val result=SamiSubtitles.toSrt("<SAMI><SYNC Start=1000><P>First<br>line<SYNC Start=2500><P>&nbsp;<SYNC Start=5000><P>Second<SYNC Start=6000><P>&nbsp;</SAMI>")!!
        assertTrue(result.contains("00:00:01,000 --> 00:00:02,500\nFirst\nline"))
        assertTrue(result.contains("00:00:05,000 --> 00:00:06,000\nSecond"))
        assertEquals(2,Regex(" --> ").findAll(result).count())
    }
    @Test fun preferredLanguageChoosesOneClassInsteadOfMixingTranslations() {
        val text="""<SAMI><STYLE>.ENCC {lang: en-US;} .FRCC {lang: fr-FR;}</STYLE>
            <SYNC Start=0><P Class=ENCC>Hello<P Class=FRCC>Bonjour
            <SYNC Start=2000><P Class=ENCC>&nbsp;<P Class=FRCC>&nbsp;</SAMI>"""
        val result=SamiSubtitles.toSrt(text,listOf("fr-CA"))!!
        assertTrue(result.contains("Bonjour"));assertFalse(result.contains("Hello"))
        assertTrue(SamiSubtitles.toSrt(text)!!.contains("Hello"))
    }
    @Test fun entitiesAndMarkupAreDecodedWithoutExecutingContent() {
        val result=SamiSubtitles.toSrt("<sami><sync start=0><p><b>A &amp; B</b> &#x1F525; &#39;yes&#39;<script>bad()</script><sync start=900><p>&nbsp;")!!
        assertTrue(result.contains("A & B 🔥 'yes'"));assertFalse(result.contains("bad()"))
    }
    @Test fun malformedEmptyOversizedAndExcessiveCueFilesAreRejected() {
        assertNull(SamiSubtitles.toSrt("<SAMI><P>Untimed"))
        assertNull(SamiSubtitles.toSrt("<SYNC start=999999999999999999999><P>Invalid"))
        assertNull(SamiSubtitles.toSrt("x".repeat(SubtitleFiles.MAX_DOWNLOAD+1)))
        assertNull(SamiSubtitles.toSrt("<SYNC start=1><P>x".repeat(50_001)))
    }
    @Test fun samiIsRecognizedByExtensionOrFileContent() {
        assertEquals("sami",SubtitleFiles.format("caption.smi",byteArrayOf()))
        assertEquals("sami",SubtitleFiles.format("caption.sami",byteArrayOf()))
        assertEquals("sami",SubtitleFiles.format("download.txt","<SAMI><BODY>".toByteArray()))
    }
}
