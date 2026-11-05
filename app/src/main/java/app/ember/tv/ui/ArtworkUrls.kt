package app.ember.tv.ui

import java.net.URI

/** Resize only known public image CDNs; arbitrary add-on/server/signed URLs stay intact. */
internal fun sizedArtworkUrl(url: String, wide: Boolean, width: Int): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    if (uri.scheme != "https" && uri.scheme != "http") return url
    val path = uri.rawPath ?: return url
    val resized = when {
        uri.host.equals("image.tmdb.org", true) -> {
            val size = if (wide) when {
                width <= 300 -> "w300"
                width <= 780 -> "w780"
                width <= 1280 -> "w1280"
                else -> "original" // The documented backdrop sizes stop at 1280.
            } else when {
                width <= 342 -> "w342"
                width <= 500 -> "w500"
                width <= 780 -> "w780"
                else -> "original"
            }
            tmdbArtworkSize.replaceFirst(path, "/t/p/$size/")
        }
        uri.host.equals("images.metahub.space", true) -> {
            val match = metahubArtworkSize.find(path)
            if (match == null) path else {
                val kind = match.groupValues[1]
                val size = if (width > (if (kind == "background") 1280 else 500)) "large" else "medium"
                path.replaceRange(match.range, "/$kind/$size/")
            }
        }
        else -> return url
    }
    if (resized == path) return url
    // Preserve raw escapes, query credentials, fragments and authority verbatim.
    val offset = url.indexOf('/', url.indexOf("://") + 3)
    return if (offset < 0) url else url.replaceRange(offset, offset + path.length, resized)
}

internal fun heroArtworkUrl(raw: String, options: RenderOptions, wide: Boolean = true): String =
    sizedArtworkUrl(raw, wide, width = if (options.preciseHero) options.heroMaxWidth
        else minOf(if (wide) 1280 else 780, options.heroMaxWidth))

private val tmdbArtworkSize = Regex("^/t/p/(original|w\\d+|h\\d+)/")
private val metahubArtworkSize = Regex("^/(background|poster)/(?:small|medium|large)/")
