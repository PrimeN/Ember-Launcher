package app.ember.tv

import app.ember.tv.player.MkvChapters
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class MkvChaptersTest {
    // EBML element: id bytes, 8-byte size, data.
    private fun el(id: Long, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = generateSequence(id) { it shr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }.toList().reversed()
        idBytes.forEach { out.write(it.toInt()) }
        out.write(0x01) // 8-byte size
        for (i in 6 downTo 0) out.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt())
        out.write(data)
        return out.toByteArray()
    }
    private fun uint(id: Long, v: Long) = el(id, ByteArray(8) { i -> ((v shr (8 * (7 - i))) and 0xFF).toByte() })
    private fun str(id: Long, v: String) = el(id, v.toByteArray())
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun atom(startNs: Long, name: String) = el(0xB6, cat(uint(0x91, startNs), el(0x80, str(0x85, name))))
    private val chapters = el(0x1043A770, el(0x45B9, cat(atom(0, "Opening"), atom(90_000_000_000, "Act two"))))
    private val ebmlHeader = el(0x1A45DFA3, str(0x4282, "matroska"))

    @Test
    fun chaptersRightAfterTheHeader() {
        val file = cat(ebmlHeader, byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01, -1, -1, -1, -1, -1, -1, -1), chapters, ByteArray(64))
        val list = MkvChapters.parseFile(file) { _, _ -> null }
        assertEquals(listOf("Opening" to 0L, "Act two" to 90_000L), list.map { it.name to it.startMs })
    }

    @Test
    fun chaptersFoundThroughTheSeekHead() {
        // SeekHead points to Chapters 5000 bytes into the segment; the head read does not reach them.
        val segHeader = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01, -1, -1, -1, -1, -1, -1, -1)
        val seek = el(0x4DBB, cat(el(0x53AB, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70)), uint(0x53AC, 5000)))
        val head = cat(ebmlHeader, segHeader, el(0x114D9B74, seek), ByteArray(32))
        val segmentStart = (ebmlHeader.size + segHeader.size).toLong()
        val list = MkvChapters.parseFile(head) { at, size ->
            assertEquals(segmentStart + 5000, at)
            chapters.copyOf(minOf(size, chapters.size))
        }
        assertEquals(2, list.size)
        assertEquals("Act two", list[1].name)
    }
}
