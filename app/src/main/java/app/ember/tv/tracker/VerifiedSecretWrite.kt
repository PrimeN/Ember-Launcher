package app.ember.tv.tracker

/** SharedPreferences can mutate memory even when commit returns false. Restore that view too. */
internal object VerifiedSecretWrite {
    fun <T> replace(previous: T, value: T, commit: (T) -> Boolean, read: () -> T): Boolean {
        val saved = try { commit(value) && read() == value } catch (_: Exception) { false }
        if (!saved) runCatching { commit(previous) }
        return saved
    }
}
