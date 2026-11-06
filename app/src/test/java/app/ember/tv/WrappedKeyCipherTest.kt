package app.ember.tv

import app.ember.tv.tracker.SecretCipher
import app.ember.tv.tracker.WrappedKeyCipher
import org.junit.Assert.*
import org.junit.Test

/** #26: caches use one Keystore-wrapped data key instead of sending every value to the Keystore. */
class WrappedKeyCipherTest {
    /** Stands in for the Keystore: counts calls and seals with a name-bound XOR. */
    private class CountingWrapper : SecretCipher {
        var calls = 0
        private fun mix(name: String, v: ByteArray) = v.mapIndexed { i, b -> (b.toInt() xor name[i % name.length].code xor 0x5A).toByte() }.toByteArray()
        override fun encrypt(name: String, value: ByteArray): ByteArray { calls++; return mix(name, value) }
        override fun decrypt(name: String, blob: ByteArray): ByteArray { calls++; return mix(name, blob) }
    }

    @Test fun manyValuesUseTheKeystoreOnceAndRoundTrip() {
        val wrapper = CountingWrapper()
        var stored: String? = null
        val cipher = WrappedKeyCipher(wrapper, "test", { stored }, { stored = it; true })
        val big = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        repeat(50) { i ->
            val name = "entry-$i"
            assertArrayEquals(big, cipher.decrypt(name, cipher.encrypt(name, big)))
        }
        assertEquals(1, wrapper.calls)
        assertNotNull(stored)
    }

    @Test fun anotherLaunchOpensTheSameKeyAndNamesAreAuthenticated() {
        val wrapper = CountingWrapper()
        var stored: String? = null
        val first = WrappedKeyCipher(wrapper, "test", { stored }, { stored = it; true })
        val blob = first.encrypt("a", "hello".toByteArray())
        val second = WrappedKeyCipher(wrapper, "test", { stored }, { stored = it; true })
        assertEquals("hello", String(second.decrypt("a", blob)))
        assertThrows(Exception::class.java) { second.decrypt("b", blob) }
        // Two encryptions of the same value differ (fresh IV each time).
        assertFalse(first.encrypt("a", "x".toByteArray()).contentEquals(first.encrypt("a", "x".toByteArray())))
    }

    @Test fun keystoreSealedEntriesFromBeforeReadAsMissesAndALostKeyIsReplaced() {
        var stored: String? = "not a wrapped key"
        val cipher = WrappedKeyCipher(CountingWrapper(), "test", { stored }, { stored = it; true })
        // Version 1 blobs were sealed by the Keystore key directly: unreadable here, so a cache miss.
        assertThrows(Exception::class.java) { cipher.decrypt("a", byteArrayOf(1) + ByteArray(40)) }
        assertEquals("ok", String(cipher.decrypt("a", cipher.encrypt("a", "ok".toByteArray()))))
        assertNotEquals("not a wrapped key", stored)
    }

    @Test fun aKeyThatCannotBeStoredFailsOnceWithoutRetryingTheKeystore() {
        val wrapper = CountingWrapper()
        val cipher = WrappedKeyCipher(wrapper, "test", { null }, { false })
        repeat(5) { assertThrows(Exception::class.java) { cipher.encrypt("a", byteArrayOf(1)) } }
        assertEquals(1, wrapper.calls)
    }
}
