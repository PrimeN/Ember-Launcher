package app.ember.tv.data

object HomeTitleVisibility {
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun prefix(profile: String) = hash(profile) + ":"
    fun key(profile: String, meta: Meta): String = prefix(profile) + hash(
        (if (meta.type == "movie") "movie:" else "tv:") + (meta.imdb ?: meta.tmdbId?.let { "tmdb:$it" } ?: meta.id))
}
