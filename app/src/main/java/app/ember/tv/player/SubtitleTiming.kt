package app.ember.tv.player

/** Nextlib scales the playhead; mpv scales subtitle timestamps. Keep one meaning in the UI. */
internal object SubtitleTiming {
    fun rate(value:Float)=if(value.isFinite())value.coerceIn(0.1f,10f) else 1f
    fun nativeSpeed(rate:Float)=1.0/SubtitleTiming.rate(rate)
    fun nativeDelay(offsetMs:Long,rate:Float,partOffsetMs:Long=0)=offsetMs/1000.0/SubtitleTiming.rate(rate)-partOffsetMs/1000.0
}
