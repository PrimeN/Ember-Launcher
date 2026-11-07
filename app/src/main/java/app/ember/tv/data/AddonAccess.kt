package app.ember.tv.data

import java.security.MessageDigest

/** Pause state and collection references use opaque identities, never configured URLs. */
object AddonAccess {
    /** Addon links are few and checked constantly (#57): each is hashed once. */
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun key(url: String): String = keys[url] ?: MessageDigest.getInstance("SHA-256")
        .digest(url.trim().toByteArray()).toHex().also { if (keys.size < 512) keys[url] = it }
    @Volatile var paused: Set<String> = emptySet()
    fun available(url: String) = key(url) !in paused
    fun requireAvailable(url: String) { check(available(url)) { "This add-on is paused." } }
}
