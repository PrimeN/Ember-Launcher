package app.ember.tv

import app.ember.tv.data.Legal
import app.ember.tv.device.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BundledCalibrationSamplesTest {
    private fun source(path: String): File = File("src/main/$path").takeIf { it.exists() } ?: File("app/src/main/$path")

    @Test fun bundledPatternsAreRegisteredInTheirGenuineFormats() {
        val all = BundledCalibrationSamples.all
        assertEquals(12, all.size)
        assertEquals(12, all.map { it.id }.toSet().size)
        assertEquals(12, all.map { it.resource }.toSet().size)
        assertTrue(all.all { it.resource != 0 && it.title != 0 })
        assertEquals(9, BundledCalibrationSamples.forKind(CalibrationClip.HDR10).size)
        assertEquals(3, BundledCalibrationSamples.forKind(CalibrationClip.DOLBY_VISION).size)
        assertTrue(BundledCalibrationSamples.forKind(CalibrationClip.SDR).isEmpty())
        all.forEach { assertEquals(it, BundledCalibrationSamples.find(it.id, it.kind)) }
    }
    @Test fun onlyKnownSamplesWithTheRequestedFormatMayBeOpened() {
        assertNull(BundledCalibrationSamples.find(null, CalibrationClip.HDR10))
        assertNull(BundledCalibrationSamples.find("../arbitrary", CalibrationClip.HDR10))
        assertNull(BundledCalibrationSamples.find("hdr_black_level", CalibrationClip.DOLBY_VISION))
        assertNull(BundledCalibrationSamples.find("dv_black", CalibrationClip.HDR10))
        assertNull(BundledCalibrationSamples.find("dv_black", null))
    }
    @Test fun includedFilesRetainTheirOriginalBytesAndHdrProvenance() {
        val manifest = Json.parseToJsonElement(source("assets/calibration/samples.json").readText()).jsonObject
        val samples = manifest.getValue("samples").jsonArray.map { it.jsonObject }
        assertEquals(BundledCalibrationSamples.all.map { it.id }.toSet(), samples.map { it.getValue("id").jsonPrimitive.content }.toSet())
        for (sample in samples) {
            val file = source("res/raw/${sample.getValue("file").jsonPrimitive.content}")
            assertTrue(file.isFile)
            assertEquals(sample.getValue("bytes").jsonPrimitive.long, file.length())
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(sample.getValue("sha256").jsonPrimitive.content, digest)
            assertEquals(3840, sample.getValue("width").jsonPrimitive.int)
            assertEquals(2160, sample.getValue("height").jsonPrimitive.int)
            val kind = CalibrationClip.valueOf(sample.getValue("kind").jsonPrimitive.content)
            assertNotNull(BundledCalibrationSamples.find(sample.getValue("id").jsonPrimitive.content, kind))
            if (kind == CalibrationClip.HDR10) assertEquals("smpte2084", sample.getValue("transfer").jsonPrimitive.content)
            else assertTrue(sample.getValue("dolby_vision").jsonPrimitive.boolean)
        }
    }
    @Test fun bothCreatorsReceiveInAppAndBundledAttribution() {
        val items = Legal.OPEN_SOURCE.flatMap { it.items }
        assertTrue(items.any { "Mehanik" in it.title && "HDR Test Patterns" in it.title })
        val notice = source("assets/licenses/HDR-calibration-NOTICE.txt").readText()
        assertTrue("Mehanik" in notice)
        assertTrue("HDR Test Patterns" in notice)
        assertTrue("YouTube" in notice)
    }
}
