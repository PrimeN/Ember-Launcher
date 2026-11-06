package app.ember.tv.tracker

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts one named value; the name is authenticated so a value cannot be moved to another name. */
internal interface SecretCipher {
    fun encrypt(name: String, value: ByteArray): ByteArray
    fun decrypt(name: String, blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore (available since Android 6, so
 * every supported Android 10+ device has it). Each value gets a fresh Keystore-chosen 12-byte IV.
 * Layout: version (1) + IV (12) + ciphertext and 16-byte tag.
 */
internal class KeystoreSecretCipher(private val alias: String = "ember_tokens_v2") : SecretCipher {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    override fun encrypt(name: String, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected IV length" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(value)
    }

    override fun decrypt(name: String, blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_BYTES && blob[0] == VERSION) { "Unknown secret format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Names stay readable (they say which service is set up, not its credentials); values are sealed. */
internal class SecretVault(private val prefs: SharedPreferences, private val cipher: SecretCipher) {
    /** Fails when the Keystore cannot create, store or use the key, so callers never write cleartext. */
    fun selfTest() {
        val probe = byteArrayOf(1, 2, 3, 4)
        check(cipher.decrypt(PROBE, cipher.encrypt(PROBE, probe)).contentEquals(probe)) { "Keystore round trip failed" }
    }

    fun get(name: String): String? = prefs.getString(name, null)?.let { open(name, it) }

    fun all(): Map<String, String> = prefs.all.mapNotNull { (name, stored) ->
        (stored as? String)?.let { open(name, it) }?.let { name to it }
    }.toMap()

    /** Null removes a value. Written synchronously; false when nothing was committed. */
    @Suppress("UseKtx") // The caller needs commit()'s result; KTX edit {} returns Unit.
    fun write(values: Map<String, String?>, clear: Boolean = false): Boolean {
        val sealed = values.mapValues { (name, value) -> value?.let { seal(name, it) } }
        return prefs.edit().apply {
            if (clear) clear()
            sealed.forEach { (name, value) -> if (value == null) remove(name) else putString(name, value) }
        }.commit()
    }

    private fun seal(name: String, value: String) =
        java.util.Base64.getEncoder().encodeToString(cipher.encrypt(name, value.toByteArray(Charsets.UTF_8)))

    // An unreadable value (Keystore reset, corrupt file) reads as absent, as if never saved.
    private fun open(name: String, stored: String): String? = runCatching {
        String(cipher.decrypt(name, java.util.Base64.getDecoder().decode(stored)), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val FILE = "ember_secrets"
        private const val PROBE = "\u0000probe"
    }
}

/**
 * Envelope encryption for caches (#26). The Keystore protects one random 256-bit data key, which is
 * unwrapped once per launch; values are then sealed with ordinary AES-256-GCM in the app.
 *
 * Sealing every cached catalog, metadata response or server image with the Keystore key directly
 * sent each value (up to megabytes) through the secure hardware, and on TV hardware such as the
 * Chromecast with Google TV that made Home slow and contended for Keystore operation slots. At
 * rest the data is protected the same way: without the Keystore key, the stored data key is
 * useless. If the wrapped key cannot be opened (Keystore reset, restore to another device), a new
 * one is made and older entries simply read as cache misses.
 * Layout: version (2) + IV (12) + ciphertext and 16-byte tag.
 */
internal class WrappedKeyCipher(
    private val wrapper: SecretCipher,
    private val label: String,
    private val loadWrapped: () -> String?,
    private val saveWrapped: (String) -> Boolean,
) : SecretCipher {
    private val random = java.security.SecureRandom()
    // Worked out once, success or failure: a broken Keystore must not be retried on every value.
    private val keyResult: Result<SecretKey> by lazy { runCatching {
        val aad = "data-key:$label"
        loadWrapped()?.let { saved ->
            runCatching {
                val raw = wrapper.decrypt(aad, java.util.Base64.getDecoder().decode(saved))
                require(raw.size == KEY_BYTES)
                javax.crypto.spec.SecretKeySpec(raw, "AES")
            }.getOrNull()
        } ?: ByteArray(KEY_BYTES).also(random::nextBytes).let { raw ->
            // Fails (no cache) rather than keeping an unwrapped key anywhere on disk.
            check(saveWrapped(java.util.Base64.getEncoder().encodeToString(wrapper.encrypt(aad, raw)))) { "Could not store the cache key" }
            javax.crypto.spec.SecretKeySpec(raw, "AES")
        }
    } }
    private val key: SecretKey get() = keyResult.getOrThrow()

    override fun encrypt(name: String, value: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return byteArrayOf(VERSION) + iv + cipher.doFinal(value)
    }

    override fun decrypt(name: String, blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_BYTES && blob[0] == VERSION) { "Unknown cache format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 2
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val PREFS = "ember_cache_keys"

        /** A cache cipher whose data key is wrapped by the Keystore key [alias]. */
        fun forCache(context: android.content.Context, alias: String): WrappedKeyCipher {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            return WrappedKeyCipher(KeystoreSecretCipher(alias), alias, { prefs.getString(alias, null) }, { prefs.edit().putString(alias, it).commit() })
        }
    }
}
