package app.ember.tv.player

import app.ember.tv.remote.RemoteBridge
import app.ember.tv.tracker.Kind

/** #241: what the phone remote sees of the player, and what it can do with it. */
internal data class RemoteNow(
    val title: String, val subtitle: String, val image: String?, val positionMs: Long, val durationMs: Long,
    val playing: Boolean, val live: Boolean, val meta: app.ember.tv.data.Meta?,
)

/** #243: a question the TV is asking, mirrored on the phone with the same choices. */
internal data class RemotePrompt(val kind: String, val title: String, val text: String, val choices: List<Pair<String, String>>)

internal object PlayerRemote {
    /** The player's own questions: the Up next card, then the Skip intro/recap/credits button. */
    fun prompt(): RemotePrompt? = RemoteBridge.onMain {
        val a = active() ?: return@onMain null
        val u = a.upNextUi
        if (a.upNextInitialized && u.visible) {
            val line = listOf(u.series, "S${u.season}:E${u.episode}" + if (u.title.isNotBlank()) " " + u.title else "").filter { it.isNotBlank() }.joinToString(" · ")
            return@onMain RemotePrompt("upnext", if (u.finding) "Finding a source for the next episode…" else "Up next in ${u.seconds}s", line,
                if (u.finding) listOf("cancel" to "Cancel") else listOf("play" to "Play now", "cancel" to "Cancel"))
        }
        val m = a.skipTarget ?: return@onMain null
        val label = when (m.kind) {
            "recap" -> a.getString(app.ember.tv.R.string.pl_skip_recap)
            "credits" -> a.getString(if (a.nextEpisodes.isNotEmpty()) app.ember.tv.R.string.ui_next_episode else app.ember.tv.R.string.pl_skip_credits)
            else -> a.getString(app.ember.tv.R.string.ui_skip_intro)
        }
        RemotePrompt("skip", label, "", listOf("skip" to label))
    }

    /** Answers one of [prompt]'s questions; false when it is no longer being asked. */
    fun answer(kind: String, choice: String): Boolean = RemoteBridge.onMain {
        val a = active() ?: return@onMain false
        when {
            kind == "upnext" && a.upNextInitialized && a.upNextUi.visible -> if (choice == "play" && !a.upNextUi.finding) a.playNext() else a.dismissUpNext()
            kind == "skip" && a.skipTarget != null -> a.doSkip()
            else -> return@onMain false
        }
        true
    } ?: false

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
            "seek" -> value?.let { target -> a.player?.let { p -> p.seekTo(target.coerceIn(0, p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)); a.showControls() }
                ?: a.controlActions.seekBy(target - c.position) }
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
