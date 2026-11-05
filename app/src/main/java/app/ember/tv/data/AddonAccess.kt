package app.ember.tv.data

import java.security.MessageDigest

/** Pause state and collection references use opaque identities, never configured URLs. */
object AddonAccess {
    fun key(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    @Volatile var paused: Set<String> = emptySet()
    fun available(url: String) = key(url) !in paused
    fun requireAvailable(url: String) { check(available(url)) { "This add-on is paused." } }
}
