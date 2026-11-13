package app.ember.tv.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * #241: who may use the phone remote. With no password, anyone on the home network can (the TV
 * says so). With one, a phone signs in once and keeps a long-lived token; the TV keeps only token
 * hashes, so reading its settings never gives a working token. Wrong passwords lock sign-in for a
 * minute after [MAX_FAILURES] tries. Nothing here is in backups: it lives in its own store.
 */
class RemoteAuth(private val store: Store, private val now: () -> Long = System::currentTimeMillis, private val iterations: Int = ITERATIONS) {
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    private val random = SecureRandom()
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    val hasPassword: Boolean get() = !store.get(PASSWORD).isNullOrBlank()

    /** Sets or replaces the password; every phone signs in again. */
    fun setPassword(password: String): String? {
        if (password.length < MIN_LENGTH) return "Use at least $MIN_LENGTH characters."
        if (password.length > 128) return "Use at most 128 characters."
        val salt = ByteArray(16).also(random::nextBytes)
        store.put(PASSWORD, b64(salt) + ":" + b64(hash(password, salt)))
        store.put(TOKENS, null)
        return null
    }

    fun clearPassword() { store.put(PASSWORD, null); store.put(TOKENS, null) }

    /** Wrong guesses in the last few minutes lock sign-in for a while. */
    @Synchronized fun locked(): Boolean = now() < lockedUntil

    @Synchronized fun check(password: String): Boolean {
        val t = now()
        if (t < lockedUntil) return false
        val saved = store.get(PASSWORD)?.split(':') ?: return false
        if (saved.size != 2) return false
        val ok = runCatching { MessageDigest.isEqual(hash(password, unb64(saved[0])), unb64(saved[1])) }.getOrDefault(false)
        if (!ok) {
            while (failures.isNotEmpty() && t - failures.first() > FAILURE_WINDOW_MS) failures.removeFirst()
            failures.addLast(t)
            if (failures.size >= MAX_FAILURES) { lockedUntil = t + LOCK_MS; failures.clear() }
        } else failures.clear()
        return ok
    }

    /** A new phone token after a correct password. At most [MAX_PHONES] stay signed in; the oldest goes first. */
    @Synchronized fun issue(): String {
        val token = b64(ByteArray(32).also(random::nextBytes))
        val kept = tokens().takeLast(MAX_PHONES - 1) + digest(token)
        store.put(TOKENS, kept.joinToString(","))
        return token
    }

    fun valid(token: String?): Boolean = !token.isNullOrBlank() && token.length < 100 && digest(token) in tokens()

    @Synchronized fun revoke(token: String?) { if (token != null) store.put(TOKENS, (tokens() - digest(token)).joinToString(",").ifEmpty { null }) }

    fun signOutAll() = store.put(TOKENS, null)

    val phones: Int get() = tokens().size

    /** Without a password the remote is open to the home network; with one, a signed-in phone. */
    fun allows(token: String?): Boolean = !hasPassword || valid(token)

    private fun tokens(): List<String> = store.get(TOKENS)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded
    private fun digest(token: String) = b64(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getUrlDecoder().decode(text)

    companion object {
        const val PASSWORD = "password"
        const val TOKENS = "tokens"
        const val MIN_LENGTH = 4
        const val MAX_FAILURES = 5
        const val FAILURE_WINDOW_MS = 5 * 60_000L
        const val LOCK_MS = 60_000L
        const val MAX_PHONES = 8
        const val ITERATIONS = 60_000

        /** The remote's own preferences file: never part of a backup. */
        const val PREFS = "ember_remote"

        fun prefsStore(context: android.content.Context): Store {
            val sp = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            return object : Store {
                override fun get(key: String) = sp.getString(key, null)
                override fun put(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
            }
        }
    }
}
