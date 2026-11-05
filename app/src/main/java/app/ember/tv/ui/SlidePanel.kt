package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text

/**
 * A full-height panel against the right edge, like the mini (quick settings) menu. Back closes it.
 * Focus starts on the first row, or on Close when [first] is not attached to anything.
 */
@Composable
internal fun SlidePanel(title: String, onClose: () -> Unit, subtitle: String? = null,
    content: @Composable ColumnScope.(first: FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val soundContext = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompactPanelContent {
            DialogMotion()
            CompositionLocalProvider(LocalFloating provides true, LocalContentColor provides Color(0xFFE8EAED)) {
                BoxWithConstraints(Modifier.fillMaxSize().guardOpeningPress().background(FloatingPanelStyle.scrim)
                    .onPreviewKeyEvent { RemoteSounds.onKey(soundContext, it.nativeKeyEvent); false }) {
                    Column(Modifier.align(Alignment.CenterEnd).padding(12.dp)
                        .width(MenuSizing.panelWidth(maxWidth * MenuSizing.COMPACT_PANEL_SCALE, androidx.compose.ui.platform.LocalDensity.current.fontScale))
                        .fillMaxHeight().floatingPanelSurface().verticalScroll(rememberMenuScrollState()).padding(bottom = 20.dp)) {
                        Row(Modifier.fillMaxWidth().background(FloatingPanelStyle.header).padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                subtitle?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 2) }
                            }
                            Chip(androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_close), modifier = Modifier.focusRequester(closeFocus), maxLines = 1, onClick = onClose)
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            content(first)
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (!runCatching { first.requestFocus() }.getOrDefault(false)) runCatching { closeFocus.requestFocus() }
            }
        }
    }
}
