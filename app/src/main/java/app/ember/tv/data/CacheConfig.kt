package app.ember.tv.data

import kotlinx.serialization.Serializable

@Serializable
data class CacheConfig(
    val imageDays: Int = 30,
    val metadataHours: Int = 24,
    val catalogHours: Int = 6,
    val networkHours: Int = 24,
) {
    fun valid() = imageDays in 1..365 && metadataHours in 1..720 && catalogHours in 1..720 && networkHours in 1..720
    fun millis(kind: CacheKind): Long = when (kind) {
        CacheKind.IMAGES -> imageDays.toLong() * 86_400_000
        CacheKind.METADATA -> metadataHours.toLong() * 3_600_000
        CacheKind.CATALOGS -> catalogHours.toLong() * 3_600_000
        CacheKind.NETWORK -> networkHours.toLong() * 3_600_000
    }
}

enum class CacheKind(val directory: String, val label: String) {
    IMAGES("images", "Images"), METADATA("metadata", "Metadata"), CATALOGS("catalogs", "Catalogs"), NETWORK("http", "Network responses")
}

/** Cache epochs are disposable namespaces, separate from the user's saved configuration. */
object CachePolicy {
    @Volatile var config = CacheConfig(); internal set
    @Volatile internal var epochs = CacheKind.entries.associateWith { java.util.UUID.randomUUID().toString() }
    fun epoch(kind: CacheKind) = epochs.getValue(kind)
    fun fresh(kind: CacheKind, at: Long, now: Long = System.currentTimeMillis(), ceiling: Long = Long.MAX_VALUE) =
        now - at in 0 until minOf(config.millis(kind), ceiling)
    internal fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
