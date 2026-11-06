package app.ember.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.ember.tv.data.Meta

internal data class PageBackdropSelection(val meta: Meta?, val dim: Float, val heightFraction: Float)

/** The page owns selection; its backdrop paints in the window behind navigation. */
internal class PageBackdropState {
    var selection by mutableStateOf<PageBackdropSelection?>(null)
    var presented by mutableStateOf<Meta?>(null)
}

internal val LocalPageBackdrop = staticCompositionLocalOf<PageBackdropState?> { null }

@Composable
internal fun PageBackdropHost(state: PageBackdropState, content: @Composable BoxScope.() -> Unit) {
    val sink by rememberUpdatedState(LocalArtworkPresented.current)
    var hadSelection by remember(state) {mutableStateOf(false)}
    LaunchedEffect(state.selection!=null) {
        if(state.selection!=null)hadSelection=true
        else if(hadSelection) {
            hadSelection=false
            state.presented=null
            sink(ReadyArtwork(null,null))
        }
    }
    CompositionLocalProvider(LocalPageBackdrop provides state) {
        Box(Modifier.fillMaxSize()) {
            state.selection?.let { selection ->
                ModernBackdrop(selection.meta, selection.dim, heightFraction = selection.heightFraction,
                    onPresented = { state.presented = it })
            }
            content()
        }
    }
}
