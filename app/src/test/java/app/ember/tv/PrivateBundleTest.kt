package app.ember.tv

import app.ember.tv.data.PrivateBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PrivateBundleTest {
    /** The same steps as privateBundle() in app/build.gradle.kts. */
    private fun seal(plain: String, pass: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, 210_000, 256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(byteArrayOf(1) + salt + iv + c.doFinal(plain.toByteArray()))
    }

    private val sample = """{"keys":{"TMDB_API_KEY":"abc"},"addons":["https://x.example/manifest.json"],
        "iptv":[{"name":"Home","kind":"xtream","url":"http://tv.example:8080","username":"u","password":"p"}],
        "collections":["https://c.example/collections.json"],"extra":1}"""

    @Test fun opensWithTheRightPassword() {
        val got = PrivateBundle.open("correct horse", seal(sample, "correct horse")).getOrThrow()
        assertEquals("abc", got.keys["TMDB_API_KEY"])
        assertEquals(1, got.addons.size)
        assertEquals("xtream", got.iptv.single().kind)
        assertEquals("ts", got.iptv.single().format)
        assertEquals(1, got.collections.size)
    }

    @Test fun refusesAWrongPassword() {
        assertTrue(PrivateBundle.open("wrong", seal(sample, "correct horse")).isFailure)
    }

    @Test fun refusesGarbage() {
        assertTrue(PrivateBundle.open("x", "bm90IGEgYnVuZGxl").isFailure)
    }
}
