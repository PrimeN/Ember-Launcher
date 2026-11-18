package app.ember.tv.player

/** Compatibility playback's video renderer: mpv's newer gpu-next (libplacebo) by default, or the older gpu. */
internal object RendererPolicy {
    val choices=listOf("gpu-next" to "Advanced (gpu-next)","gpu" to "Stable (gpu)")
    /** "auto" and anything unknown mean gpu-next; only an explicit "gpu" keeps the older renderer. */
    fun initial(value:String)=if(value=="gpu")"gpu" else "gpu-next"
    fun label(value:String)=choices.first {it.first==initial(value)}.second
}

/** At most one automatic retry on gpu; only an explicit choice resets recovery. */
internal class RendererSession(initial:String,private val videoEnabled:Boolean=true) {
    var active=initial;private set
    var recovered=false;private set
    fun select(value:String){active=value;recovered=false}
    fun fallback():Boolean {
        if(!videoEnabled||active!="gpu-next"||recovered)return false
        active="gpu";recovered=true;return true
    }
    fun initialize(open:(String)->Unit,recreate:()->Unit) {
        try {open(active)}catch(error:Throwable) {
            if(error !is Exception && error !is LinkageError || !fallback())throw error
            recreate();open(active)
        }
    }
}
