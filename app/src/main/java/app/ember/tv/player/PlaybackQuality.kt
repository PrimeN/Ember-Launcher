package app.ember.tv.player

/** #411 (from Ember-Player): frames delivered while a route played, for "remember what worked". */
internal data class PlaybackQuality(val renderedFrames: Int? = null, val droppedFrames: Int? = null) {
    /** Unknown video delivery is not evidence of a working video route; at most 2% of frames dropped. */
    fun learnable(video: Boolean): Boolean {
        if (!video) return true
        val rendered = renderedFrames ?: return false
        val dropped = droppedFrames ?: return false
        return rendered >= 120 && dropped >= 0 && dropped.toLong() * 100 <= (rendered.toLong() + dropped) * 2
    }
}

internal data class FrameCount(val rendered: Int, val dropped: Int) {
    fun since(start: FrameCount): PlaybackQuality? {
        if (start.rendered < 0 || start.dropped < 0 || rendered < start.rendered || dropped < start.dropped) return null
        return PlaybackQuality(renderedFrames = rendered - start.rendered, droppedFrames = dropped - start.dropped)
    }
}

/** Counts from one decoder only, starting after a seek, pause or decoder change, once it has played [STABLE_PLAYBACK_MS]. */
internal class PlaybackQualityWindow<T : Any>(
    private val decoder: T,
    private val startedAt: Long,
    private val baseline: FrameCount,
) {
    fun measure(currentDecoder: T, now: Long, current: FrameCount): PlaybackQuality? =
        if (decoder === currentDecoder && now - startedAt >= STABLE_PLAYBACK_MS) current.since(baseline) else null
}

internal const val STABLE_PLAYBACK_MS = 15_000L
