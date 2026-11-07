package app.ember.tv.data

object HomeTitleVisibility {
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).toHex()
    /** Home asks for every title on each refresh; the same few hundred keys are reused (#57). */
    private val keys = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2048
    }
    private fun cached(value: String) = synchronized(keys) { keys.getOrPut(value) { hash(value) } }
    fun prefix(profile: String) = cached(profile) + ":"
    fun key(profile: String, meta: Meta): String = prefix(profile) + cached(
        (if (meta.type == "movie") "movie:" else "tv:") + (meta.imdb ?: meta.tmdbId?.let { "tmdb:$it" } ?: meta.id))
}
