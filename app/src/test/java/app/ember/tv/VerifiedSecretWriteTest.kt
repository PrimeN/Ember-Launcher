package app.ember.tv

import app.ember.tv.tracker.VerifiedSecretWrite
import org.junit.Assert.*
import org.junit.Test

class VerifiedSecretWriteTest {
    @Test fun failedDiskWriteAlsoRestoresTheOldInMemoryCredential() {
        var current: String? = "old-fixture"
        val saved = VerifiedSecretWrite.replace(current, "new-fixture", { current = it; false }, { current })
        assertFalse(saved); assertEquals("old-fixture", current)
    }
    @Test fun failedReadbackRestoresExistingConfiguration() {
        var current = mapOf("server" to "old-fixture")
        var reads = 0
        val saved = VerifiedSecretWrite.replace(current, mapOf("other" to "new-fixture"), { current = it; true }, { reads++; emptyMap() })
        assertFalse(saved); assertEquals(mapOf("server" to "old-fixture"), current); assertEquals(1, reads)
    }
    @Test fun successfulWriteIsVerifiedWithoutRollback() {
        var current: String? = null; var writes = 0
        assertTrue(VerifiedSecretWrite.replace(current, "fixture", { writes++; current = it; true }, { current }))
        assertEquals("fixture", current); assertEquals(1, writes)
    }
    @Test fun failedFirstSaveRemovesTheUnconfirmedMemoryValue() {
        var current: String? = null
        assertFalse(VerifiedSecretWrite.replace(current, "fixture", { current = it; false }, { current }))
        assertNull(current)
    }
}
