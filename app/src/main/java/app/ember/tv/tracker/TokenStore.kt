package app.ember.tv.tracker

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Keeps sign-in tokens, API keys and private source details encrypted with an AES-256-GCM key
 * held in the Android Keystore (see [SecretVault]). Opening it touches the Keystore, so every
 * TokenStore shares one copy per process.
 *
 * Older versions used androidx.security-crypto's EncryptedSharedPreferences ("ember_tokens"),
 * and before that a plain file ("ember_tokens_plain"). Both are moved into the Keystore store
 * once, verified, and only then cleared ([TokenMigration]). When the Keystore is unusable,
 * existing plain values stay readable but nothing new is written in cleartext.
 */
class TokenStore(context: Context) {
    private val legacy=context.applicationContext.getSharedPreferences(TokenMigration.PLAIN,Context.MODE_PRIVATE)
    private val vault: SecretVault? = shared(context.applicationContext)

    companion object {
        // Reset by reflection in tests; a failed open is not cached, so a later call can retry.
        @Volatile private var instance: SecretVault? = null

        private fun shared(context: Context): SecretVault? =
            instance ?: synchronized(this) { instance ?: open(context)?.also { instance = it } }

        private fun open(context: Context): SecretVault? = runCatching {
            val vault=SecretVault(context.getSharedPreferences(SecretVault.FILE,Context.MODE_PRIVATE),KeystoreSecretCipher())
            vault.selfTest()
            TokenMigration.run(context,vault)
            vault
        }.getOrNull()
    }

    private val lock: Any get() = vault ?: legacy

    fun get(key: String): String? = vault?.get(key) ?: legacy.getString(key,null)
    fun put(key: String, value: String) {putAll(mapOf(key to value))}
    fun putAll(values:Map<String,String>) {
        check(writeEncrypted(values)) {"Encrypted storage is unavailable or credentials could not be saved."}
    }
    val encryptionAvailable: Boolean get() = vault != null
    /** Private source data must never fall back to cleartext when the keystore is unavailable. */
    fun putEncrypted(key: String, value: String): Boolean=writeEncrypted(mapOf(key to value))
    private fun writeEncrypted(values:Map<String,String>):Boolean = synchronized(lock) {
        val store=vault ?: return@synchronized false
        val previous=values.keys.associateWith {store.get(it)}
        val expected:Map<String,String?> = values
        val saved = VerifiedSecretWrite.replace(previous,expected,
            { next -> store.write(next) },
            {values.keys.associateWith {store.get(it)}})
        if (saved) legacy.edit(commit = true) {values.keys.forEach {remove(it)}}
        saved
    }
    fun remove(vararg keys: String) = synchronized(lock) {
        vault?.write(keys.associateWith {null})
        legacy.edit { keys.forEach { remove(it) } }
    }

    /** Every saved token and key, for backups. */
    fun all(): Map<String, String> = synchronized(lock) {
        legacy.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap() + vault?.all().orEmpty()
    }

    /** Replaces everything with [values], for restoring a backup. Written synchronously. */
    fun replaceAll(values: Map<String, String>) = synchronized(lock) {
        check(values.isEmpty() || vault != null) { "Encrypted storage is unavailable. Restore was not applied." }
        vault?.let { store ->
            val saved = VerifiedSecretWrite.replace(store.all(), values, { next -> store.write(next, clear = true) }, store::all)
            check(saved) { "Could not save restored credentials. Previous configuration was kept." }
        }
        legacy.edit(commit = true) { clear() }
        Unit
    }
}

/** Moves values from the older stores into [SecretVault] once, clearing them only after verification. */
internal object TokenMigration {
    const val PLAIN = "ember_tokens_plain"
    /** androidx.security-crypto EncryptedSharedPreferences file used up to October 2026. */
    const val ENCRYPTED = "ember_tokens"
    private const val META = "ember_tokens_meta"
    private const val DONE = "keystoreMigrated"

    fun run(context: Context, vault: SecretVault) {
        val meta=context.getSharedPreferences(META,Context.MODE_PRIVATE)
        val plain=context.getSharedPreferences(PLAIN,Context.MODE_PRIVATE)
        val oldFile=context.getSharedPreferences(ENCRYPTED,Context.MODE_PRIVATE)
        if(meta.getBoolean(DONE,false) && plain.all.isEmpty() && oldFile.all.isEmpty()) return
        // A Keystore problem that hides the old values must not mark them migrated: retry next start.
        val old = if (oldFile.all.isEmpty()) emptyMap() else readEncrypted(context) ?: return
        val values = strings(plain) + old
        val pending = values.filter { (k, _) -> vault.get(k) == null }
        if (pending.isNotEmpty() && !vault.write(pending)) return
        if (!values.all { (k, v) -> vault.get(k) == v || k !in pending }) return
        oldFile.edit(commit = true) { clear() }
        plain.edit(commit = true) { clear() }
        meta.edit(commit = true) { putBoolean(DONE, true) }
    }

    private fun strings(prefs: SharedPreferences) = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    @Suppress("DEPRECATION")
    private fun readEncrypted(context: Context): Map<String, String>? = runCatching {
        val key = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        strings(androidx.security.crypto.EncryptedSharedPreferences.create(context, ENCRYPTED, key,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
    }.getOrNull()
}
