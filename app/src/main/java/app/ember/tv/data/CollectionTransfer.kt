package app.ember.tv.data

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable collection-only export. Private arguments never leave the TV without encryption. */
object CollectionTransfer {
    const val FORMAT = "ember-collection-pack"
    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun seal(values: List<MediaCollection>, passphrase: String, read: (String) -> String?): String {
        require(passphrase.length in 8..1024) { "Use a passphrase of 8–1024 characters." }
        val portable = values.map { c -> c.copy(folders = c.folders.map { f ->
            fun unpack(s: CollectionSource) = s.copy(extraArgs = CollectionSecrets.resolve(s, read), secretArgsRef = null)
            f.copy(sources = f.sources?.map(::unpack), catalogSources = f.catalogSources.map(::unpack))
        }) }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes); val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(FORMAT.toByteArray())
        val data = cipher.doFinal(Collections.encode(portable).toByteArray(Charsets.UTF_8))
        fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        return buildJsonObject { put("format", FORMAT); put("version", 1); put("salt", b64(salt)); put("iv", b64(iv)); put("data", b64(data)) }.toString()
    }
    fun open(text: String, passphrase: String): String {
        require(text.length <= 8_000_000) { "The collection pack is too large." }
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if ((root?.get("format") as? JsonPrimitive)?.contentOrNull != FORMAT) return text
        try {
            require(root["version"]?.jsonPrimitive?.intOrNull == 1 && passphrase.length in 8..1024)
            fun bytes(name: String) = Base64.getDecoder().decode(root[name]!!.jsonPrimitive.content)
            val salt = bytes("salt"); val iv = bytes("iv"); require(salt.size == 16 && iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)); cipher.updateAAD(FORMAT.toByteArray())
            return String(cipher.doFinal(bytes("data")), Charsets.UTF_8)
        } catch (_: Exception) { error("Could not unlock this collection pack. Check the passphrase and file.") }
    }
}
