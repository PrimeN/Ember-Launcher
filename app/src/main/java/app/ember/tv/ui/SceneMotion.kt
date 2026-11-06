package app.ember.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Animate only the incoming draw layer: no duplicate outgoing page or delayed focus registration. */
@Composable internal fun Modifier.sceneEnter(scene:Any?,panel:Boolean=false):Modifier {
    val duration=PolishMotion.duration(if(panel)TvTokens.Motion.Panel else TvTokens.Motion.Page,LocalRenderOptions.current,LocalReduceMotion.current)
    if(duration==0)return this
    return key(scene) {
        var entered by remember{mutableStateOf(false)}
        LaunchedEffect(Unit){entered=true}
        val progress by animateFloatAsState(if(entered)1f else 0f,tween(duration),label="sceneEnter")
        graphicsLayer {alpha=progress}
    }
}

/**
 * Let Android own dialog enter/exit lifetimes; Compose never keeps an outgoing focus tree.
 * With live glass (High image quality, Frosted glass on) the system also blurs what is behind the
 * popup, on Android 12+ TVs that allow cross-window blur; it turns off again if the TV stops
 * allowing it (for example while a tunnelled video plays or in battery saving).
 */
@Composable internal fun DialogMotion() {
    val enabled=PolishMotion.duration(TvTokens.Motion.Panel,LocalRenderOptions.current,LocalReduceMotion.current)>0
    val window=(LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window,enabled) {
        window?.setWindowAnimations(if(enabled)app.ember.tv.R.style.EmberPanelMotion else 0)
        onDispose { }
    }
    DialogBackdrop(window)
}
