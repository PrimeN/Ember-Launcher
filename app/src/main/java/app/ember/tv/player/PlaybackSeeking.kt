package app.ember.tv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/** Only interactive navigation borrows a different tolerance; resume and markers retain theirs. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackSeeking {
    fun target(position:Long,increment:Long,duration:Long) =
        (position+increment).coerceIn(0L,duration.takeIf {it>0} ?: Long.MAX_VALUE)
    fun seek(player:ExoPlayer,target:Long,fast:Boolean) {
        val previous=player.seekParameters
        player.setSeekParameters(if(fast)SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        try {player.seekTo(target)} finally {player.setSeekParameters(previous)}
    }
}
