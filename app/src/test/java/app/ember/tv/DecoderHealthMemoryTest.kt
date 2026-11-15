package app.ember.tv

import app.ember.tv.player.*
import org.junit.Assert.*
import org.junit.Test

class DecoderHealthMemoryTest {
    @Test fun provenChoiceSurvivesReloadButExpiresAndRejectsClockRollback() {
        var now=100L
        val health=DecoderHealthMemory("device",clock={now})
        health.remember("hevc",ProvenDecoder("hardware.hevc",savedAt=now))
        assertEquals("hardware.hevc",DecoderHealthMemory("device",health.document()){now}.get("hevc")?.name)
        now=99;assertNull(health.get("hevc"))
        now=100+DecoderHealthMemory.TTL;assertNull(health.get("hevc"))
    }
    @Test fun firmwareOrAppEnvironmentChangeDiscardsOldChoices() {
        val old=DecoderHealthDocument("old",mapOf("codec" to ProvenDecoder("decoder",savedAt=10)))
        assertNull(DecoderHealthMemory("new",old){11}.get("codec"))
    }
    @Test fun memoryStaysBoundedAndForgettingOneFailureKeepsOtherChoices() {
        val health=DecoderHealthMemory("device",clock={1000})
        repeat(200){health.remember("codec$it",ProvenDecoder("decoder$it",savedAt=it.toLong()))}
        assertEquals(128,health.document().entries.size);assertNull(health.get("codec0"))
        health.forget("codec199");assertNull(health.get("codec199"));assertNotNull(health.get("codec198"))
        health.clear();assertTrue(health.document().entries.isEmpty())
    }
    @Test fun oversizedRestoredMemoryIsBoundedBeforeItCanBeUsed() {
        val old=DecoderHealthDocument("device",(0..200).associate {"codec$it" to ProvenDecoder("decoder",savedAt=it.toLong())})
        assertEquals(128,DecoderHealthMemory("device",old){1000}.document().entries.size)
    }
}
