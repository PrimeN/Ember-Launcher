package app.ember.tv

import app.ember.tv.iptv.EncryptedIptvFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.spec.SecretKeySpec

class IptvEncryptionTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private fun cache() = EncryptedIptvFiles(folder.root) { key }
    private fun read(cache: EncryptedIptvFiles, name: String = "channels") = cache.read(name) { it.bufferedReader().readText() }
    private val credentials = "https://provider.example/live/test-user/test-secret/channel.ts"

    @Test fun encryptedRoundTripPreservesCredentialsWithoutPlaintextAndUsesFreshIv() {
        val cache = cache()
        assertTrue(cache.write("channels") { it.write(credentials.toByteArray()) }.isSuccess)
        val first = File(folder.root, "channels.bin").readBytes()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("test-secret"))
        assertEquals(credentials, read(cache).getOrThrow())
        cache.write("channels") { it.write(credentials.toByteArray()) }.getOrThrow()
        assertFalse(first.contentEquals(File(folder.root, "channels.bin").readBytes()))
        assertEquals(credentials, read(cache).getOrThrow())
    }
    @Test fun tamperAndTruncationNeverPublishEvenWithEarlyDecoderReturn() {
        val cache = cache()
        cache.write("channels") { it.write(ByteArray(50_000) { 42 }) }.getOrThrow()
        val file = File(folder.root, "channels.bin")
        val good = file.readBytes()
        file.writeBytes(good.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertTrue(cache.read("channels") { "stopped early" }.isFailure)
        file.writeBytes(good.copyOf(good.size - 8))
        assertTrue(read(cache).isFailure)
    }
    @Test fun wrongKeyAndWrongFilePurposeAreRejected() {
        val cache = cache()
        cache.write("channels") { it.write(credentials.toByteArray()) }.getOrThrow()
        File(folder.root, "channels.bin").copyTo(File(folder.root, "guide.bin"))
        assertTrue(read(cache, "guide").isFailure)
        val other = EncryptedIptvFiles(folder.root) { SecretKeySpec(ByteArray(32) { 77 }, "AES") }
        assertTrue(read(other).isFailure)
    }
    @Test fun failedWriteKeepsThePreviousAuthenticatedCacheAndCleansTemporaryFile() {
        val cache = cache()
        cache.write("channels") { it.write(credentials.toByteArray()) }.getOrThrow()
        assertTrue(cache.write("channels") { it.write("partial".toByteArray()); error("disk full") }.isFailure)
        assertEquals(credentials, read(cache).getOrThrow())
        assertEquals(listOf("channels.bin"), folder.root.list()!!.filter { it != EncryptedIptvFiles.KEY_FILE })
    }
    @Test fun keystoreIsUsedOncePerReadOrWriteNotPerChunk() {
        // #58: a guide of several MB was thousands of Keystore calls.
        var calls = 0
        val counting = EncryptedIptvFiles(folder.root) { calls++; key }
        val big = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        counting.write("guide") { it.write(big) }.getOrThrow()
        assertTrue("write used the Keystore $calls times", calls <= 2)
        calls = 0
        assertArrayEquals(big, counting.read("guide") { it.readBytes() }.getOrThrow())
        assertEquals(1, calls)
        assertFalse(File(folder.root, EncryptedIptvFiles.KEY_FILE).readBytes().toString(Charsets.ISO_8859_1).contains("test-secret"))
    }
    @Test fun filesWrittenBeforeTheChangeStillOpen() {
        val iv = ByteArray(12) { 3 }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv)); updateAAD("channels".toByteArray())
        }
        File(folder.root, "channels.bin").writeBytes(byteArrayOf(69, 73, 80, 84, 1) + iv + cipher.doFinal(credentials.toByteArray()))
        assertEquals(credentials, read(cache()).getOrThrow())
        cache().write("channels") { it.write(credentials.toByteArray()) }.getOrThrow()
        assertEquals(2, File(folder.root, "channels.bin").readBytes()[4].toInt())
        assertEquals(credentials, read(cache()).getOrThrow())
    }
    @Test fun migrationDeletesLegacyOnlyAfterEncryptedReadback() {
        val legacy = folder.newFile("channels.json").apply { writeText(credentials) }
        val migrated = cache().readMigrating("channels", legacy, { it.bufferedReader().readText() }, { data, out -> out.write(data.toByteArray()) })
        assertEquals(credentials, migrated.getOrThrow())
        assertFalse(legacy.exists())
        assertEquals(credentials, read(cache()).getOrThrow())
    }
    @Test fun unavailableKeystorePreservesLegacyAndCreatesNoPlaintextFallback() {
        val legacy = folder.newFile("channels.json").apply { writeText(credentials) }
        val unavailable = EncryptedIptvFiles(folder.root) { error("keystore unavailable") }
        assertTrue(unavailable.readMigrating("channels", legacy, { it.bufferedReader().readText() }, { data, out -> out.write(data.toByteArray()) }).isFailure)
        assertEquals(credentials, legacy.readText())
        assertEquals(listOf("channels.json"), folder.root.list()!!.toList())
        assertTrue(unavailable.write("guide") { it.write(credentials.toByteArray()) }.isFailure)
        assertEquals(listOf("channels.json"), folder.root.list()!!.toList())
    }
    @Test fun failedMigrationReadbackKeepsOriginalForRecovery() {
        val legacy = folder.newFile("channels.json").apply { writeText(credentials) }
        var calls = 0
        val changingKey = EncryptedIptvFiles(folder.root) {
            if (calls++ == 0) key else SecretKeySpec(ByteArray(32) { 8 }, "AES")
        }
        assertTrue(changingKey.readMigrating("channels", legacy, { it.bufferedReader().readText() }, { data, out -> out.write(data.toByteArray()) }).isFailure)
        assertEquals(credentials, legacy.readText())
    }
}
