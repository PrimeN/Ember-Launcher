package app.ember.tv.player

import dev.jdtech.mpv.MPVLib

/** End-file errors belong to one file. A later load or intentional stop must not inherit them. */
internal class NativeFileEvents(private val defer:(Runnable,Long)->Unit,private val canRecover:()->Boolean,private val recover:()->Unit) {
    private var generation=0L
    private var pending=false
    fun started(){generation++;pending=false}
    fun ended(reason:Int) {
        if(reason!=MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR || pending)return
        pending=true
        val visit=generation
        defer(Runnable {if(visit==generation && canRecover())recover()},500)
    }
}
