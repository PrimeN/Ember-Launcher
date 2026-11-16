package app.ember.tv.player

import android.app.ActivityManager
import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
data class StreamConfig(
    val custom:Boolean=false,val connectSeconds:Int=20,val readSeconds:Int=30,
    val forwardMiB:Int=128,val rewindMiB:Int=16,val aheadSeconds:Int=120,
    val refillSeconds:Int=5,val pauseForBuffer:Boolean=true,val readAheadSeconds:Int=20,
) {
    fun normalized()=copy(connectSeconds=connectSeconds.coerceIn(5,120),readSeconds=readSeconds.coerceIn(5,300),
        forwardMiB=forwardMiB.coerceIn(8,512),rewindMiB=rewindMiB.coerceIn(0,128),aheadSeconds=aheadSeconds.coerceIn(10,600),
        refillSeconds=refillSeconds.coerceIn(1,30),readAheadSeconds=readAheadSeconds.coerceIn(0,300))
    companion object {
        fun plan(config:PlaybackConfig,heap:Long,low:Boolean):StreamPlan {
            val p=config.stream.normalized()
            val budget=(minOf(192L,heap/(4*1024*1024)).coerceAtLeast(16)).toInt().coerceAtMost(if(low)24 else 192)
            val requested=if(p.custom)p.forwardMiB else when(config.buffer){"normal"->48;"huge"->160;else->96}
            val rewind=if(p.custom)p.rewindMiB else when(config.buffer){"normal"->8;"huge"->32;else->16}
            val total=(requested+rewind).coerceAtMost(budget)
            val backward=if(rewind==0)0 else minOf(rewind,total/3,(total.toLong()*rewind/(requested+rewind)).toInt())
            val ahead=if(p.custom)p.aheadSeconds else when(config.buffer){"normal"->60;"huge"->240;else->120}
            val refill=(if(p.custom)p.refillSeconds else 5).coerceAtMost(ahead)
            return StreamPlan(total-backward,backward,ahead,refill,if(p.custom)p.pauseForBuffer else true,
                if(p.custom)p.readAheadSeconds else 20,p.connectSeconds,p.readSeconds)
        }
        fun plan(context:Context,config:PlaybackConfig):StreamPlan {
            val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory=ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            return plan(config,Runtime.getRuntime().maxMemory(),manager.isLowRamDevice||memory.lowMemory)
        }
    }
}
data class StreamPlan(val forwardMiB:Int,val rewindMiB:Int,val aheadSeconds:Int,val refillSeconds:Int,
    val pauseForBuffer:Boolean,val readAheadSeconds:Int,val connectSeconds:Int,val readSeconds:Int) {
    val totalBytes get()=(forwardMiB+rewindMiB)*1024*1024
    fun nativeOptions()=linkedMapOf("demuxer-max-bytes" to (forwardMiB*1024L*1024).toString(),
        "demuxer-max-back-bytes" to (rewindMiB*1024L*1024).toString(),"cache-secs" to aheadSeconds.toString(),
        "cache-pause" to if(pauseForBuffer)"yes" else "no","cache-pause-wait" to refillSeconds.toString(),
        "demuxer-readahead-secs" to readAheadSeconds.toString(),"network-timeout" to readSeconds.toString())
}
