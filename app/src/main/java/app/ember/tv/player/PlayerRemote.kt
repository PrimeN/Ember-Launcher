package app.ember.tv.player

import app.ember.tv.remote.RemoteBridge
import app.ember.tv.tracker.Kind

/** #241: what the phone remote sees of the player, and what it can do with it. */
internal data class RemoteNow(
    val title: String, val subtitle: String, val image: String?, val positionMs: Long, val durationMs: Long,
    val playing: Boolean, val live: Boolean, val meta: app.ember.tv.data.Meta?,
)

internal object PlayerRemote {
    private fun active(): PlayerActivity? = PlayerActivity.current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun now(): RemoteNow? = RemoteBridge.onMain { active()?.remoteNow() }

    /** "toggle", "play", "pause", "seek" (to [value] ms), "skip" (by [value] ms), "stop" or "next". */
    fun command(action: String, value: Long?): Boolean = RemoteBridge.onMain {
        val a = active() ?: return@onMain false
        val c = a.controls
        when (action) {
            "toggle" -> a.controlActions.playPause()
            "play" -> if (!c.playing) a.controlActions.playPause()
            "pause" -> if (c.playing) a.controlActions.playPause()
            "seek" -> value?.let { target -> a.controlActions.seekBy(target.coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE) - c.position) }
            "skip" -> value?.let { a.controlActions.seekBy(it) }
            "stop" -> a.endPlayback()
            "next" -> if (c.nextEpisodes.isNotEmpty()) a.controlActions.next() else return@onMain false
            else -> return@onMain false
        }
        true
    } ?: false

    private fun PlayerActivity.remoteNow(): RemoteNow {
        val c = controls
        val meta = if (!itemInitialized || live || trailer) null else app.ember.tv.data.Meta(id = item.imdbId ?: resumeKey,
            type = if (item.kind == Kind.MOVIE) "movie" else "series", name = seriesName, imdbId = item.imdbId, tmdbId = item.tmdbId,
            season = item.season, episode = item.episode, serverRef = serverRef)
        val subtitle = listOfNotNull(c.seasonEpisode, c.details?.episodeTitle?.takeIf { it.isNotBlank() }, c.details?.year).joinToString(" · ")
        return RemoteNow(c.title.ifBlank { seriesName }, subtitle, c.details?.backdrop ?: if (itemInitialized) item.poster else null, c.position, c.duration, c.playing, live, meta)
    }
}
