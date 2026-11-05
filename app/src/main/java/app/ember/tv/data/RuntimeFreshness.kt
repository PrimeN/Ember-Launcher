package app.ember.tv.data

/** Runtime-only caches use elapsed time; never accept a negative age or a future timestamp. */
internal fun runtimeFresh(at: Long, now: Long, ttl: Long): Boolean =
    at >= 0L && now >= at && ttl > 0L && now - at < ttl
