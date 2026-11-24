package app.ember.tv

import app.ember.tv.player.StatsDot
import app.ember.tv.player.StreamStatsFormat
import app.ember.tv.player.StreamStatsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #415: the stream info panel's readings and colours. */
class StreamStatsTest {
    @Test fun dolbyVisionProfileFromCodecs() {
        assertEquals("Profile 7.6 · BL + EL (dual layer)", StreamStatsFormat.dolbyVision("dvhe.07.06"))
        assertEquals("Profile 8.6 · single layer · HDR10 base", StreamStatsFormat.dolbyVision("dvh1.08.06"))
        assertEquals("Profile 5.9 · single layer · IPT", StreamStatsFormat.dolbyVision("dvhe.05.09"))
        assertNull(StreamStatsFormat.dolbyVision("hvc1.2.4.L153.B0"))
        assertNull(StreamStatsFormat.dolbyVision(null))
    }

    @Test fun masteringFromHdrStaticInfo() {
        val b = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0)
        // P3 D65 primaries in 0.00002 units: R(0.680,0.320) G(0.265,0.690) B(0.150,0.060), white (0.3127,0.3290).
        listOf(34000, 16000, 13250, 34500, 7500, 3000, 15635, 16450).forEach { b.putShort(it.toShort()) }
        b.putShort(4000.toShort()); b.putShort(50.toShort()); b.putShort(1000.toShort()); b.putShort(400.toShort())
        assertEquals("P3 · MDL 4000 nits (min 0.0050) · CLL 1000 · FALL 400", StreamStatsFormat.mastering(b.array()))
        b.putShort(21, 0); b.putShort(23, 0)
        assertTrue(StreamStatsFormat.mastering(b.array())!!.endsWith("CLL/FALL not set"))
        assertNull(StreamStatsFormat.mastering(ByteArray(3)))
    }

    @Test fun healthColours() {
        assertEquals(StatsDot.GOOD, StreamStatsFormat.buffer(12.7, 60_000, 7_200_000, 72_700))
        assertEquals(StatsDot.WARN, StreamStatsFormat.buffer(5.0, 60_000, 7_200_000, 65_000))
        assertEquals(StatsDot.BAD, StreamStatsFormat.buffer(1.0, 60_000, 7_200_000, 61_000))
        assertEquals(StatsDot.NONE, StreamStatsFormat.buffer(1.0, 2_000, 7_200_000, 3_000))
        assertEquals(StatsDot.GOOD, StreamStatsFormat.buffer(1.0, 7_197_000, 7_200_000, 7_198_000))
        assertEquals(StatsDot.GOOD, StreamStatsFormat.speed(150_000_000, 73_000_000))
        assertEquals(StatsDot.WARN, StreamStatsFormat.speed(80_000_000, 73_000_000))
        assertEquals(StatsDot.BAD, StreamStatsFormat.speed(40_000_000, 73_000_000))
        assertEquals(StatsDot.NONE, StreamStatsFormat.speed(40_000_000, null))
        assertEquals(StatsDot.WARN, StreamStatsFormat.ping(140))
        val st = StreamStatsState()
        assertEquals(StatsDot.GOOD, st.recency(0, 0, 100_000))
        assertEquals(StatsDot.BAD, st.recency(3, 90_000, 100_000))
        assertEquals(StatsDot.WARN, st.recency(3, 10_000, 100_000))
    }

    @Test fun refreshMatchesWholeMultiples() {
        assertTrue(StreamStatsFormat.refreshMatches(23.976f, 23.976f))
        assertTrue(StreamStatsFormat.refreshMatches(47.952f, 23.976f))
        assertTrue(StreamStatsFormat.refreshMatches(50f, 25f))
        assertFalse(StreamStatsFormat.refreshMatches(60f, 23.976f))
        assertFalse(StreamStatsFormat.refreshMatches(0f, 24f))
    }
}
