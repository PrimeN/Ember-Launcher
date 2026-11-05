package app.ember.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

enum class CollectionMediaKind { IMAGE, VIDEO }

/** Motion is opt-in and belongs only to the currently focused, foreground collection. */
object CollectionMotion {
    fun allowed(enabled: Boolean, focused: Boolean, active: Boolean, reduced: Boolean, kids: Boolean = false) =
        enabled && focused && active && !reduced && !kids
    fun remote(url: String?) = url?.toHttpUrlOrNull() != null
    fun knownKind(url: String?): CollectionMediaKind? = when (url?.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
        "mp4", "m4v", "webm", "mkv", "mov", "m3u8", "mpd", "ts" -> CollectionMediaKind.VIDEO
        "gif", "webp", "apng", "png", "jpg", "jpeg", "avif" -> CollectionMediaKind.IMAGE
        else -> null
    }
    fun kind(url: String?, videoHint: Boolean) = knownKind(url) ?: if (videoHint) CollectionMediaKind.VIDEO else CollectionMediaKind.IMAGE
    fun imageBound(lowPower: Boolean) = if (lowPower) 512 else 960
    fun imageCacheKey(animate: Boolean, lowPower: Boolean) = "${if (animate) "motion" else "still"}:${imageBound(lowPower)}"
    fun nextAttempt(url: String, attempt: Int) = if (knownKind(url) == null && attempt == 0) 1 else 2
}

/** A stale tile's disposal must never release the newer tile's preview decoder. Main-thread owner. */
internal class ExclusiveCollectionPreview<T : Any>(private val release: (T) -> Unit) {
    private var current: T? = null
    fun claim(next: T) {
        if (current !== next) {
            val previous = current; current = next
            previous?.let(release)
        }
    }
    fun drop(value: T) { if (current === value) { current = null; release(value) } }
}
