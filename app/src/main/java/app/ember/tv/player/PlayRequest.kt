package app.ember.tv.player

import android.content.Context
import android.content.Intent
import app.ember.tv.data.Episode
import app.ember.tv.data.StreamOption
import app.ember.tv.data.SubtitleOption
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.TrackedItem
import java.util.Locale

/** Everything a player needs to play one source and report progress. */
data class PlayRequest(
    val stream: StreamOption,
    val title: String,
    val kind: Kind,
    val imdbId: String?,
    val tmdbId: Long?,
    val season: Int?,
    val episode: Int?,
    /** Key for the saved position: the episode id or the title id. */
    val resumeKey: String,
    /** 0..1 from a tracker, used when there is no saved position on this TV. */
    val resumeFraction: Float?,
    val subtitles: List<SubtitleOption>,
    /** Series name without the episode suffix, for "Up next" titles. */
    val seriesName: String = title,
    /** Episodes after this one, in order, for auto-play. Empty for movies. */
    val nextEpisodes: List<Episode> = emptyList(),
    /** Set for media server titles, so the player can report progress back to the server. */
    val serverRef: String? = null,
    /** Start from the beginning even when there is a resume point. */
    val startOver: Boolean = false,
    /** Resume or Start over is already decided (not asked again). */
    val resumeDecided: Boolean = false,
    /** What the advanced player shows: logo, year, rating, synopsis, cast. */
    val details: PlayerDetails? = null,
    val profileId: String? = null,
    val contentMeta: app.ember.tv.data.Meta? = null,
) {
    /** What trackers need to scrobble this play. */
    fun trackedItem() = TrackedItem(
        key = resumeKey,
        title = title,
        kind = kind,
        tmdbId = tmdbId,
        imdbId = imdbId,
        season = season,
        episode = episode,
    )

    fun toIntent(context: Context): Intent {
        details?.let { PlayerDetails.put(resumeKey, it) }
        // Every addon subtitle goes to the player, which ranks them with the downloaded ones.
        val privatePlayback = stream.serverPlayback
        // The source's own subtitle tracks come first (#74).
        val subs = if (privatePlayback == null) (stream.subtitles + subtitles).distinctBy { it.url }.take(60) else emptyList()
        return Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_URL, stream.url.takeIf { privatePlayback == null })
            .putExtra(EXTRA_HEADER_NAMES, stream.headers.keys.toTypedArray())
            .putExtra(EXTRA_HEADER_VALUES, stream.headers.values.toTypedArray())
            .putExtra(EXTRA_SERVER_SESSION, privatePlayback?.let(app.ember.tv.mediaserver.ServerPlaybackRegistry::put))
            .putExtra(EXTRA_SERVER_SOURCE, privatePlayback?.sourceId)
            .putExtra(EXTRA_SERVER_QUALITY, privatePlayback?.quality?.key)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_PROFILE, profileId)
            .putExtra(EXTRA_AGE_RATING,contentMeta?.ageRating)
            .putExtra(EXTRA_GENRES,contentMeta?.genres?.toTypedArray())
            .putExtra(EXTRA_ADULT,contentMeta?.adult==true)
            .putExtra(EXTRA_CONTENT_TYPE,contentMeta?.type)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_IMDB, imdbId)
            .putExtra(EXTRA_TMDB, tmdbId ?: -1L)
            .putExtra(EXTRA_SEASON, season ?: -1)
            .putExtra(EXTRA_EPISODE, episode ?: -1)
            .putExtra(EXTRA_RESUME_KEY, resumeKey)
            .putExtra(EXTRA_RESUME_FRACTION, resumeFraction ?: -1f)
            .putExtra(EXTRA_SUB_URLS, subs.map { it.url }.toTypedArray())
            .putExtra(EXTRA_SUB_LANGS, subs.map { it.lang }.toTypedArray())
            .putExtra(EXTRA_SERIES_NAME, seriesName)
            .putExtra(EXTRA_RELEASE, stream.info?.filename ?: stream.title.lineSequence().firstOrNull { it.isNotBlank() })
            .putExtra(EXTRA_PREV_ADDON, stream.addonName)
            .putExtra(EXTRA_PREV_QUALITY, stream.quality)
            .putExtra(EXTRA_BINGE_GROUP, stream.bingeGroup)
            .putExtra(EXTRA_VIDEO_HASH, stream.videoHash)
            .putExtra(EXTRA_VIDEO_SIZE, stream.videoSize ?: -1L)
            .putExtra(EXTRA_NEXT_IDS, nextEpisodes.map { it.id }.toTypedArray())
            .putExtra(EXTRA_NEXT_SEASONS, nextEpisodes.map { it.season }.toIntArray())
            .putExtra(EXTRA_NEXT_NUMBERS, nextEpisodes.map { it.episode }.toIntArray())
            .putExtra(EXTRA_NEXT_TITLES, nextEpisodes.map { it.title }.toTypedArray())
            .putExtra(EXTRA_SERVER_REF, privatePlayback?.ref?.value ?: serverRef)
            .putExtra(EXTRA_START_OVER, startOver)
    }

    companion object {
        const val EXTRA_AGE_RATING="ageCertification"
        const val EXTRA_GENRES="contentGenres"
        const val EXTRA_ADULT="adultContent"
        const val EXTRA_CONTENT_TYPE="contentType"
        const val EXTRA_PROFILE="watchProfile"
        const val EXTRA_SERVER_QUALITY = "serverQuality"
        const val EXTRA_SERVER_SESSION = "serverSession"
        const val EXTRA_SERVER_SOURCE = "serverSource"
        const val EXTRA_URL = "url"
        /** The addon's request headers for the stream, as parallel name and value arrays. */
        const val EXTRA_HEADER_NAMES = "headerNames"
        const val EXTRA_HEADER_VALUES = "headerValues"
        fun headers(intent: Intent): Map<String, String> {
            val names = intent.getStringArrayExtra(EXTRA_HEADER_NAMES) ?: return emptyMap()
            val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES) ?: return emptyMap()
            return app.ember.tv.data.StreamHeaders.clean(names.zip(values))
        }
        const val EXTRA_START_OVER = "startOver"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND = "kind"
        const val EXTRA_IMDB = "imdb"
        const val EXTRA_TMDB = "tmdb"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_RESUME_KEY = "resumeKey"
        const val EXTRA_RESUME_FRACTION = "resumeFraction"
        const val EXTRA_SUB_URLS = "subUrls"
        /** The source's file or release name, for matching subtitles to it. */
        const val EXTRA_RELEASE = "release"
        const val EXTRA_SUB_LANGS = "subLangs"
        const val EXTRA_SERIES_NAME = "seriesName"
        const val EXTRA_PREV_ADDON = "prevAddon"
        const val EXTRA_PREV_QUALITY = "prevQuality"
        const val EXTRA_BINGE_GROUP = "bingeGroup"
        const val EXTRA_VIDEO_HASH = "videoHash"
        const val EXTRA_VIDEO_SIZE = "videoSize"
        const val EXTRA_NEXT_IDS = "nextIds"
        const val EXTRA_NEXT_SEASONS = "nextSeasons"
        const val EXTRA_NEXT_NUMBERS = "nextNumbers"
        const val EXTRA_NEXT_TITLES = "nextTitles"
        const val EXTRA_SERVER_REF = "serverRef"

        /**
         * Subtitles to attach to the player: up to 4 in the TV's language, plus 2 in English.
         * Every attached subtitle is downloaded with the video, and a dead link can stop
         * playback, so the list is kept short.
         */
        fun orderedSubtitles(subs: List<SubtitleOption>): List<SubtitleOption> {
            val codes = deviceLanguageCodes()
            val english = setOf("en", "eng", "english")
            val mine = subs.filter { it.lang.lowercase() in codes }.take(4)
            val extra = if (codes.any { it in english }) emptyList() else subs.filter { it.lang.lowercase() in english }.take(2)
            return (mine + extra).ifEmpty { subs.take(3) }
        }

        /** "en", "eng" and "english" for an English TV, and so on. */
        fun deviceLanguageCodes(): Set<String> {
            val loc = Locale.getDefault()
            return listOfNotNull(
                loc.language,
                runCatching { loc.getISO3Language() }.getOrNull(),
                loc.getDisplayLanguage(Locale.ENGLISH),
            ).map { it.lowercase() }.toSet()
        }
    }
}
