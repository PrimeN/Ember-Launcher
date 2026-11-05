package app.ember.tv.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ember.tv.data.AtomicFile
import java.io.*
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Atomic, authenticated cache files. A failed keystore/write never creates a plaintext fallback. */
class EncryptedIptvFiles(private val dir: File, private val key: () -> SecretKey = ::androidKey) {
    private val magic = byteArrayOf(69, 73, 80, 84, 1)
    @Synchronized fun write(name: String, block: (OutputStream) -> Unit): Result<Unit> = runCatching {
        dir.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
        AtomicFile.replace(File(dir, "$name.bin")) { tmp ->
            FileOutputStream(tmp).use { raw ->
                raw.write(magic); raw.write(cipher.iv)
                val shield = object : FilterOutputStream(raw) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
                    override fun close() { flush() }
                }
                CipherOutputStream(shield, cipher).use(block)
                raw.fd.sync()
            }
        }.getOrThrow()
    }

    @Synchronized fun <T> read(name: String, decode: (InputStream) -> T): Result<T> = runCatching {
        FileInputStream(File(dir, "$name.bin")).use { raw ->
            val header = ByteArray(magic.size); require(raw.read(header) == header.size && header.contentEquals(magic))
            val iv = ByteArray(12); require(raw.read(iv) == iv.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(name.toByteArray())
            }
            CipherInputStream(raw, cipher).use { plain ->
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
