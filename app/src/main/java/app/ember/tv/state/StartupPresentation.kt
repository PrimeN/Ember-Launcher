package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Essential local milestones only. Artwork and network sources never gate Home. */
internal enum class StartupStage { RESTORING, PREPARING, IGNITING, READY }

/** One presentation per ViewModel lifetime; returning Home never restarts it. */
internal class StartupPresentation {
    var stage by mutableStateOf(StartupStage.RESTORING); private set
    val ready get()=stage==StartupStage.READY
    val frameShown get()=stage>=StartupStage.IGNITING
    private fun advance(next:StartupStage) {if(next>stage)stage=next}
    fun preferencesRestored()=advance(StartupStage.PREPARING)
    fun firstFrameShown()=advance(StartupStage.IGNITING)
    fun controlsReady() {if(frameShown)advance(StartupStage.READY)}
    /** A deliberate key can reveal the laid-out Home before the automatic focus handoff completes. */
    fun revealForInput() {if(frameShown)advance(StartupStage.READY)}

    companion object {
        /** The longest the curtain stays once Home has drawn its first frame (#33). */
        const val MAX_CURTAIN_MS=2_000L
    }
}
