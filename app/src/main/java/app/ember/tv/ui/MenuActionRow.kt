package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme

/** Measurement and rendering must share font family and letter spacing as well as size. */
@Composable
internal fun actionMenuTextStyle():TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize=TvTokens.Type.Compact.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium,
    platformStyle=PlatformTextStyle(includeFontPadding=false))

/** One compact menu row. Center the content inside the entire hit area, including its minimum height. */
@Composable
internal fun MenuActionRow(label: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.MoreHoriz,
    destructive: Boolean = false, selected: Boolean = false, divider: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scheme=MaterialTheme.colorScheme
    val focusedFill=if(destructive)scheme.errorContainer else PillColor
    val focusedInk=if(destructive)scheme.onErrorContainer else OnPillColor
    val color = if (focused) focusedInk else if(destructive)scheme.error else if(selected)scheme.secondary else Fg
    if(divider)Box(Modifier.fillMaxWidth().padding(vertical=5.dp).height(1.dp).background(Fg.copy(alpha=.10f)))
    Box(Modifier.fillMaxWidth()) {
        Focusable(onClick, modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
            .semantics { contentDescription = label; if(selected)this.selected=true },
            radius = 12, container = if(selected)scheme.secondary.copy(alpha=.12f) else Color.Transparent,
            focusedContainer = focusedFill, focusedContent = focusedInk, outline = false, focusedScale = 1f) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.fillMaxWidth().padding(horizontal = MenuSizing.ACTION_PADDING, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(MenuSizing.ACTION_GAP), verticalAlignment = Alignment.CenterVertically) {
                    MaterialGlyph(icon, color, MenuSizing.ACTION_ICON)
                    Text(MenuSizing.actionLabel(label), color = color, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = actionMenuTextStyle(),
                        modifier = Modifier.weight(1f).clearAndSetSemantics { })
                }
            }
        }
        ToolbarHint(label, focused)
    }
}
