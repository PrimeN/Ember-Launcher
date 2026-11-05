package app.ember.tv.mediaserver

import app.ember.tv.data.Meta
import app.ember.tv.tracker.*
import kotlinx.serialization.json.JsonElement

/** Plex's cloud watchlist is independent of a server's local rating keys and artwork auth. */
internal object PlexPersonalLists {
    fun items(body: JsonElement): List<Meta> = body.obj("MediaContainer").arr("Metadata").list().mapNotNull { item ->
        val type = item.str("type")?.takeIf { it in setOf("movie", "show") } ?: return@mapNotNull null
        val key = item.str("ratingKey") ?: return@mapNotNull null
        val guids = item.arr("Guid").list().mapNotNull { it.str("id") }
        Meta(id = "plexwatchlist:$key", type = if (type == "movie") "movie" else "series",
            name = item.str("title") ?: return@mapNotNull null,
            imdbId = guids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://"),
            tmdbId = guids.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toLongOrNull(),
            releaseInfo = item.int("year")?.toString(), description = item.str("summary"),
            poster = item.str("thumb")?.takeIf { it.startsWith("https://") }?.let(ServerMetadata::cleanPath),
            background = item.str("art")?.takeIf { it.startsWith("https://") }?.let(ServerMetadata::cleanPath),
            progressSource = "Plex")
    }
}
