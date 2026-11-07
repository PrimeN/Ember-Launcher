package app.ember.tv.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ember.tv.data.AtomicFile
import java.io.*
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Atomic, authenticated cache files. A failed keystore/write never creates a plaintext fallback.
 *
 * The files are encrypted in memory with a 256-bit data key that is kept wrapped (encrypted) by
 * the Keystore key [key] (#58). Using the Keystore key directly made every few hundred bytes of a
 * guide a separate Keystore call; now each read or write makes one, to unwrap the data key.
 * Files from before (format 1, encrypted with the Keystore key itself) still open, and the next
 * save rewrites them.
 */
class EncryptedIptvFiles(private val dir: File, private val key: () -> SecretKey = ::androidKey) {
    private val magic = byteArrayOf(69, 73, 80, 84, 2)
    private val legacyMagic = byteArrayOf(69, 73, 80, 84, 1)
    private val keyMagic = byteArrayOf(69, 73, 80, 75, 1)
    private val keyFile get() = File(dir, KEY_FILE)

    /** The data key, unwrapped with one Keystore call; null when none was saved yet. */
    private fun savedDataKey(): SecretKey? {
        if (!keyFile.isFile) return null
        val blob = keyFile.readBytes()
        require(blob.size > keyMagic.size + 12 && blob.copyOf(keyMagic.size).contentEquals(keyMagic)) { "Damaged IPTV key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, keyMagic.size, 12)); updateAAD(KEY_PURPOSE)
        }
        val raw = cipher.doFinal(blob, keyMagic.size + 12, blob.size - keyMagic.size - 12)
        require(raw.size == 32) { "Damaged IPTV key" }
        return SecretKeySpec(raw, "AES")
    }

    /** For writing: the saved data key, or a new one (wrapped and saved) when it is missing or unreadable. */
    private fun dataKeyForWriting(): SecretKey = runCatching { savedDataKey() }.getOrNull() ?: run {
        val raw = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(KEY_PURPOSE) }
        val wrapped = keyMagic + cipher.iv + cipher.doFinal(raw)
        dir.mkdirs()
        AtomicFile.write(keyFile, wrapped).getOrThrow()
        SecretKeySpec(raw, "AES")
    }

    @Synchronized fun write(name: String, block: (OutputStream) -> Unit): Result<Unit> = runCatching {
        val dataKey = dataKeyForWriting()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, dataKey); updateAAD(name.toByteArray()) }
        AtomicFile.replace(File(dir, "$name.bin")) { tmp ->
            FileOutputStream(tmp).use { raw ->
                raw.write(magic); raw.write(cipher.iv)
                val shield = object : FilterOutputStream(raw) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
                    override fun close() { flush() }
                }
                BufferedOutputStream(CipherOutputStream(shield, cipher), 64 * 1024).use(block)
                raw.fd.sync()
            }
        }.getOrThrow()
    }

    @Synchronized fun <T> read(name: String, decode: (InputStream) -> T): Result<T> = runCatching {
        FileInputStream(File(dir, "$name.bin")).use { raw ->
            val header = ByteArray(magic.size); require(raw.read(header) == header.size)
            val fileKey = when {
                header.contentEquals(magic) -> requireNotNull(savedDataKey()) { "Missing IPTV key" }
                header.contentEquals(legacyMagic) -> key()
                else -> error("Not an Ember IPTV cache")
            }
            val iv = ByteArray(12); require(raw.read(iv) == iv.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, fileKey, GCMParameterSpec(128, iv)); updateAAD(name.toByteArray())
            }
            BufferedInputStream(CipherInputStream(BufferedInputStream(raw, 64 * 1024), cipher), 64 * 1024).use { plain ->
                val result = decode(plain)
                // Authenticate all remaining bytes before publishing even if the decoder stops early.
                val discard = ByteArray(8192)
                while (plain.read(discard) >= 0) { /* drain to authentication tag */ }
                result
            }
        }
    }

    /** Keep the original if encryption/readback fails; never erase credentials to finish migration. */
    @Synchronized fun <T> readMigrating(name: String, legacy: File, decode: (InputStream) -> T, encode: (T, OutputStream) -> Unit): Result<T> {
        if (File(dir, "$name.bin").exists()) return read(name, decode).also {
            if (it.isSuccess) legacy.delete()
        }
        return runCatching {
            val original = legacy.inputStream().buffered().use(decode)
            write(name) { encode(original, it) }.getOrThrow()
            val verified = read(name, decode).getOrThrow()
            legacy.delete()
            verified
        }
    }

    companion object {
        /** The wrapped data key, next to the cache files. */
        const val KEY_FILE = "iptv-data-key.bin"
        private val KEY_PURPOSE = "ember-iptv-data-key".toByteArray()
        private const val ALIAS = "ember_iptv_cache_v1"
        @Synchronized private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }
}
