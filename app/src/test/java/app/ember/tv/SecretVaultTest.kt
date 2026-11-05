package app.ember.tv

import app.ember.tv.tracker.SecretCipher
import app.ember.tv.tracker.SecretVault
import app.ember.tv.tracker.TokenMigration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Keystore storage: values sealed and bound to their names; old stores cleared only after verification. */
class SecretVaultTest {
    @get:Rule val temp = TemporaryFolder()

    /** Stand-in for the Keystore cipher: reversible, and rejects a value read under another name. */
    private class FixtureCipher : SecretCipher {
        override fun encrypt(name: String, value: ByteArray) = name.toByteArray() + 0.toByte() + value.map { (it.toInt() xor 0x5A).toByte() }
        override fun decrypt(name: String, blob: ByteArray): ByteArray {
            val prefix = name.toByteArray() + 0.toByte()
            check(blob.size >= prefix.size && blob.copyOf(prefix.size).contentEquals(prefix)) { "wrong name" }
            return blob.copyOfRange(prefix.size, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test fun valuesAreNeverStoredInCleartextAndCannotBeMovedBetweenNames() {
        val ctx = PersistenceContext(temp.root)
        val vault = SecretVault(ctx.getSharedPreferences(SecretVault.FILE, 0), FixtureCipher())
        vault.selfTest()
        assertTrue(vault.write(mapOf("trakt.access" to "fixture-token", "tmdb" to "fixture-key")))
        val raw = ctx.getSharedPreferences(SecretVault.FILE, 0).all
        assertFalse(raw.values.any { it.toString().contains("fixture") })
        assertEquals("fixture-token", vault.get("trakt.access"))
        // A sealed value copied under another name reads as absent rather than leaking or crashing.
        ctx.getSharedPreferences(SecretVault.FILE, 0).edit().putString("moved", raw["tmdb"] as String).commit()
        assertNull(vault.get("moved"))
        assertEquals(mapOf("trakt.access" to "fixture-token", "tmdb" to "fixture-key"), vault.all())
        assertTrue(vault.write(mapOf("tmdb" to null)))
        assertNull(vault.get("tmdb"))
        assertTrue(vault.write(mapOf("only" to "fixture-restore"), clear = true))
        assertEquals(mapOf("only" to "fixture-restore"), vault.all())
    }

    @Test fun plainTokensMoveOnceAndNewerVaultValuesWin() {
        val ctx = PersistenceContext(temp.root)
        val vault = SecretVault(ctx.getSharedPreferences(SecretVault.FILE, 0), FixtureCipher())
        assertTrue(vault.write(mapOf("shared" to "fixture-newer")))
        ctx.getSharedPreferences(TokenMigration.PLAIN, 0).edit()
            .putString("old", "fixture-old").putString("shared", "fixture-stale").commit()
        TokenMigration.run(ctx, vault)
        assertEquals("fixture-old", vault.get("old"))
        assertEquals("fixture-newer", vault.get("shared"))
        assertTrue(ctx.getSharedPreferences(TokenMigration.PLAIN, 0).all.isEmpty())
        // A second start has nothing left to move and changes nothing.
        TokenMigration.run(ctx, vault)
        assertEquals(mapOf("old" to "fixture-old", "shared" to "fixture-newer"), vault.all())
    }

    @Test fun unreadableOldEncryptedStoreIsKeptForALaterAttempt() {
        val ctx = PersistenceContext(temp.root)
        val vault = SecretVault(ctx.getSharedPreferences(SecretVault.FILE, 0), FixtureCipher())
        // Without a working Keystore the old EncryptedSharedPreferences cannot be opened in this test.
        ctx.getSharedPreferences(TokenMigration.ENCRYPTED, 0).edit().putString("opaque", "fixture-sealed").commit()
        ctx.getSharedPreferences(TokenMigration.PLAIN, 0).edit().putString("old", "fixture-old").commit()
        TokenMigration.run(ctx, vault)
        // Opening it may add its own keysets; the unreadable value itself must survive.
        assertEquals("fixture-sealed", ctx.getSharedPreferences(TokenMigration.ENCRYPTED, 0).all["opaque"])
        assertEquals(mapOf("old" to "fixture-old"), ctx.getSharedPreferences(TokenMigration.PLAIN, 0).all)
        assertTrue(vault.all().isEmpty())
    }
}
