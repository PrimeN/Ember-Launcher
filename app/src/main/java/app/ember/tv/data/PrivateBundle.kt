package app.ember.tv.data

import app.ember.tv.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The owner's private set (API keys, addons, IPTV sources, Collections), built into the APK encrypted from the
 * EMBER_PRIVATE_BUNDLE and EMBER_PRIVATE_PASSWORD secrets (see app/build.gradle.kts). Opened from
 * About by tapping "0.1 First light" ten times and entering the password. Nothing readable is in the APK.
 */
object PrivateBundle {
    @Serializable
    data class Iptv(
        val name: String = "",
        /** "m3u" or "xtream". */
        val kind: String = "m3u",
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val epg: String = "",
        val userAgent: String = "",
        val format: String = "ts",
    )

    @Serializable
    data class Contents(
        val keys: Map<String, String> = emptyMap(),
        val addons: List<String> = emptyList(),
        val iptv: List<Iptv> = emptyList(),
        /** Links to Collection exports (JSON), imported like Settings > Stremio > Collections. */
        val collections: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether this build carries a private set at all. */
    val available: Boolean get() = BuildConfig.PRIVATE_BUNDLE.isNotEmpty()

    /** Decrypts with [password]; fails on a wrong password (GCM checks it) or a malformed set. Slow: call off the main thread. */
    fun open(password: String, blob: String = BuildConfig.PRIVATE_BUNDLE): Result<Contents> = runCatching {
        val all = Base64.getDecoder().decode(blob)
        require(all.size > 1 + 16 + 12 + 16 && all[0] == 1.toByte()) { "Unknown format" }
        val salt = all.copyOfRange(1, 17)
        val iv = all.copyOfRange(17, 29)
        val ct = all.copyOfRange(29, all.size)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 210_000, 256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        json.decodeFromString(Contents.serializer(), String(c.doFinal(ct), Charsets.UTF_8))
    }
}
