package app.ember.tv.data

/** Effects only: choosing a preset retains the user's artwork and video quality settings. */
object EffectPresets {
    data class Preset(val id:String,val label:String,val description:String,val effects:CustomPerformance,val glass:Boolean)
    val options=listOf(
        Preset("fast","Fast","Sharp artwork with quiet, immediate controls.",CustomPerformance(false,false,false,false,false,0),false),
        Preset("balanced","Balanced","Subtle motion and focused colours, with restrained prefetch.",CustomPerformance(false,true,true,false,false,1),false),
        Preset("cinematic","Cinematic","Richer motion, framing, frost and opted-in previews.",CustomPerformance(true,true,true,true,true,2),true))
}

/** Two sustained slow windows are required; transient startup/one-off frames do not retune Auto. */
internal class FrameBudgetEvidence(private val budgetNanos:Long,private val window:Int=120) {
    private var count=0;private var slow=0;private var slowWindows=0
    fun add(duration:Long):Boolean {
        if(duration<=0 || duration>1_000_000_000L)return false
        count++;if(duration>budgetNanos.coerceAtLeast(1)*2)slow++
        if(count<window.coerceAtLeast(1))return false
        slowWindows=if(slow.toDouble()/count>=.2)slowWindows+1 else 0
        count=0;slow=0
        return slowWindows>=2
    }
}
