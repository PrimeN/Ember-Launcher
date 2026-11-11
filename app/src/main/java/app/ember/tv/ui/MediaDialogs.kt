package app.ember.tv.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/** The dialog Close icon, top right, as every dialog has it (#106). */
@Composable
fun DialogClose(close: () -> Unit, modifier: Modifier = Modifier) {
    ToolbarIcon(androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_close), Icons.Outlined.Close, modifier, onClick = close)
}

@Composable
fun MediaDialog(title: String, close: () -> Unit, resizeForKeyboard: Boolean = false, initialFocus: FocusRequester? = null, scrollBody: Boolean = true,
    /** #204: editors and pickers with tiles, cards or many choices take the TV's width instead of a narrow column. */
    wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    TvDialog(close,width=if(wide)MenuSizing.WIDE_DIALOG else 680.dp,height=if(wide)MenuSizing.WIDE_DIALOG_HEIGHT else 540.dp,resizeForKeyboard=resizeForKeyboard) {
        val first = remember { FocusRequester() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogHeading(title, Modifier.weight(1f))
            ToolbarIcon(androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_close), Icons.Outlined.Close, Modifier.focusRequester(first), onClick = close)
        }
        Column(Modifier.weight(1f, fill = !scrollBody).then(if (scrollBody) Modifier.verticalScroll(rememberMenuScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

        LaunchedEffect(Unit) { if (initialFocus == null || !runCatching { initialFocus.requestFocus() }.getOrDefault(false)) first.requestFocus() }
    }
}

@Composable
fun SynopsisDialog(title: String, description: String, close: () -> Unit) = MediaDialog(title, close) {
    val scroll = rememberMenuScrollState()
    val scope = rememberCoroutineScope()
    Box(Modifier.heightIn(max = 350.dp).verticalScroll(scroll).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else {
            val delta = when {
                event.key == Key.DirectionDown && scroll.canScrollForward -> 80f
                event.key == Key.DirectionUp && scroll.canScrollBackward -> -80f
                else -> 0f
            }
            if (delta == 0f) false else { scope.launch { scroll.scrollBy(delta) }; true }
        }
    }) { Text(description, fontSize = 17.sp, lineHeight = 26.sp) }
}

/** Cancel owns initial focus: holding Select cannot confirm a newly opened destructive prompt. */
@Composable
internal fun ConfirmDestructive(title:String,message:String,close:()->Unit,action:String?=null,confirm:()->Unit) {
    val cancel=remember {FocusRequester()}
    MediaDialog(title,close,initialFocus=cancel) {
        Text(message,color=Mute,fontSize=TvTokens.Type.CompactBody.sp)
        ButtonRow {
            Chip(androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_cancel),modifier=Modifier.focusRequester(cancel),onClick=close)
            Chip(action ?: androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_delete)) {confirm();close()}
        }
    }
}
