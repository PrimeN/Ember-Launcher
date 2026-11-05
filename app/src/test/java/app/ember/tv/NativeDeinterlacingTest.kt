package app.ember.tv

import app.ember.tv.player.NativeDeinterlacing
import app.ember.tv.player.PlaybackConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties

class NativeDeinterlacingTest {
    @Test fun defaultIsOffAndSavedModesRoundTrip() {
        assertEquals("off", Json.decodeFromString<PlaybackConfig>("{}").deinterlacing)
        for (mode in listOf("off", "auto", "always")) {
            val config = PlaybackConfig(deinterlacing = mode)
            assertEquals(mode, Json.decodeFromString<PlaybackConfig>(Json.encodeToString(config)).deinterlacing)
        }
        assertEquals("no", NativeDeinterlacing.mode("off"))
        assertEquals("auto", NativeDeinterlacing.mode("auto"))
        assertEquals("yes", NativeDeinterlacing.mode("always"))
        assertEquals("no", NativeDeinterlacing.mode("unsupported-future-mode"))
    }

    @Test fun defaultRetainsZeroCopyAndCpuFiltersGetReadableFramesWithoutOverridingSoftwareChoice() {
        assertEquals("mediacodec,mediacodec-copy", NativeDeinterlacing.decoder(false, "off"))
        for (mode in listOf("auto", "always")) assertEquals("mediacodec-copy", NativeDeinterlacing.decoder(false, mode))
        for (mode in listOf("off", "auto", "always")) assertEquals("no", NativeDeinterlacing.decoder(true, mode))
    }

    @Test fun everyShippedArchitectureHasThePinnedFilterLibrary() {
        val root = File("native/ffmpeg-filters")
        val properties = Properties().apply { File(root, "checksums.properties").inputStream().use { load(it) } }
        for ((abi, machine) in mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40, "x86" to 3, "x86_64" to 62)) {
            val data = File(root, "$abi/libavfilter.so").readBytes()
            assertEquals(machine, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt())
            assertEquals(properties.getProperty("$abi.sha256"), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
            val strings = data.toString(Charsets.ISO_8859_1)
            for (name in listOf("bwdif\u0000", "yadif\u0000", "LIBAVFILTER_11\u0000")) assertTrue("$abi: $name", name in strings)
        }
    }
}
