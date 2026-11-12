package app.ember.tv.phone

import java.security.SecureRandom

/** A revocable, fixed-duration lease. Traffic never extends the deadline. */
class PhoneSetupAccess internal constructor(
    durationMillis: Long = DURATION_MILLIS,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val started = nowMillis()
    private val duration = durationMillis.coerceAtLeast(1)
    @Volatile private var revoked = false
    internal val code = SecureRandom().let { random ->
        CharArray(5) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
    }
    private var failures = 0
    private var failureWindow = started

    val remainingMillis: Long get() = if (revoked) 0 else (duration - (nowMillis() - started).coerceAtLeast(0)).coerceAtLeast(0)
    val active: Boolean get() = remainingMillis > 0
    /** Bound guesses across all clients; a shorter, typeable link must not allow unlimited guesses. */
    @Synchronized internal fun accepts(value: String?): Boolean {
        if (!active) return false
        resetFailureWindow()
        if (failures >= MAX_FAILURES) return false
        if (value == code) return true
        // Only a supplied key is a guess. Browsers fetch /favicon.ico and similar without one on
        // every page; counting those would lock out the phone that holds the right link.
        if (!value.isNullOrEmpty()) failures++
        return false
    }
    internal val throttled: Boolean
        @Synchronized get() { resetFailureWindow(); return failures >= MAX_FAILURES }
    private fun resetFailureWindow() {
        val now = nowMillis()
        if (now - failureWindow >= FAILURE_WINDOW_MILLIS) { failures = 0; failureWindow = now }
    }
    internal fun revoke() { revoked = true }
    fun requireActive() { check(active) { "Phone setup is off or its session has expired." } }

    companion object {
        /** #235: half an hour, so a longer edit (badges, collections) fits in one session. */
        const val DURATION_MILLIS = 30 * 60 * 1000L
        internal const val MAX_FAILURES = 10
        internal const val FAILURE_WINDOW_MILLIS = 30_000L
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
