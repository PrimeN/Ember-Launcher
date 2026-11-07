package app.ember.tv.data

import android.content.Intent
import android.graphics.drawable.Drawable
import kotlinx.serialization.Serializable

/** An app installed on the TV that can be launched. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val intent: Intent,
    /** Optional preloaded artwork; installed-app discovery leaves it lazy. */
    val icon: Drawable? = null,
    val artworkVersion: Long = 0L,
)

/** A movie or series entry from a Stremio-style catalog. */
@Serializable
data class Meta(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    /** The title's logo (transparent PNG), as Stremio addons send it; drawn over landscape art. */
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    /** 0..1 watched fraction, set for Continue watching items. */
    val progress: Float? = null,
    val imdbId: String? = null,
    val tmdbId: Long? = null,
    /** Set for Continue watching episodes so Details can open on the right one. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Legacy transport field, cleared on migration/save. Native references resolve fresh URLs. */
    val playUrl: String? = null,
    val serverRef: String? = null,
    val serverName: String? = null,
    /** From the addon's details, e.g. ["Animation", "Action"]. Used to spot anime for AniList ratings. */
    val genres: List<String>? = null,
    /** As addons give it, e.g. "136 min". Used to work out a source's bitrate from its size. */
    val runtime: String? = null,
    /** A short line on cards, e.g. "Next: S2 · E5" (Up next) or "Tonight 9:00 PM · S3 · E1" (Airing this week). */
    val badge: String? = null,
    /** Human-readable origin of a resume point; never an account identifier or URL. */
    val progressSource: String? = null,
    val serverSeriesRef: String? = null,
    val episodeThumbnail: String? = null,
    /** Explicit provider completion; a partially watched show is never inferred complete. */
    val watched: Boolean? = null,
    /** Age certification, never the community/IMDb review score. */
    val ageRating: String? = null,
    val adult: Boolean? = null,
    /** Optional normalized composition point for this exact background image, not a face-recognition result. */
    val backgroundFocusX: Float? = null,
    val backgroundFocusY: Float? = null,
    /** Stremio's posterShape: "poster" (2:3, default), "square" (channel logos, music) or "landscape" (#72). */
    val posterShape: String? = null,
) {
    /** Height over width for this title's card. */
    val cardRatio: Float get() = when (posterShape?.lowercase()) { "square" -> 1f; "landscape" -> 0.5625f; else -> 1.5f }
    /** How much wider than a portrait card this title's card is: wide art gets more room. */
    val cardWidthScale: Float get() = if (posterShape.equals("landscape", true)) 1.6f else 1f
    /** Square and landscape art is the addon's own; portrait rating posters would be cropped to fit. */
    val isPortraitArt: Boolean get() = posterShape == null || posterShape.equals("poster", true)
    /** Provider floats can be non-finite; they must never enter layout fractions. */
    val safeProgress:Float? get()=progress?.takeIf {it.isFinite()}?.coerceIn(0f,1f)

    /** Running time in minutes, when [runtime] says. */
    val runtimeMinutes: Int?
        get() = runtime?.let { r ->
            val h = Regex("""(\d+)\s*h""").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("""(\d+)\s*m""").find(r)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (h == 0) Regex("""\d+""").find(r)?.value?.toIntOrNull() else null
            (h * 60 + (m ?: 0)).takeIf { it > 0 }
        }

    /**
     * Copy for saving in plain preferences: drops progress and anything that carries a
     * Plex or Jellyfin sign-in token. Native identity is retained for server-only titles.
     */
    fun withoutServerSecrets(): Meta = app.ember.tv.mediaserver.ServerMetadata.safe(this).copy(progress=null)

    /** Stremio catalogs such as Cinemeta use the IMDb id ("tt...") as the item id. */
    val imdb: String? get() = imdbId ?: id.takeIf { it.startsWith("tt") }?.substringBefore(':')

    /** True for the same title, matched by id, IMDb, TMDB, or name and year. */
    fun sameTitle(other: Meta): Boolean = (type == "movie") == (other.type == "movie") && (
        (!serverRef.isNullOrBlank() && serverRef == other.serverRef) ||
        (!serverSeriesRef.isNullOrBlank() && serverSeriesRef == (other.serverSeriesRef ?: other.serverRef)) ||
        (!other.serverSeriesRef.isNullOrBlank() && other.serverSeriesRef == serverRef) ||
        (id.isNotBlank() && id == other.id) ||
        (imdb != null && imdb == other.imdb) ||
        (tmdbId != null && tmdbId == other.tmdbId) ||
        (name.equals(other.name, ignoreCase = true) && (releaseInfo == null || other.releaseInfo == null ||
            releaseInfo.take(4) == other.releaseInfo.take(4))))
}

@Serializable
data class MetasResponse(val metas: List<Meta> = emptyList())

/** One horizontal row on Home, built from one addon catalog. */
data class CatalogRow(
    val addonName: String,
    val title: String,
    val items: List<Meta>,
    /** True for "Continue on <server>" rows, which also appear in Library. */
    val inProgress: Boolean = false,
    /** For addon catalog rows: the catalog it shows (CatalogDef.key). */
    val catalogKey: String? = null,
) {
    /** Resolve aliases at display time so cached rows can be renamed or restored offline. */
    val displayTitle: String get() = CatalogNames.label(catalogKey, title)
}
