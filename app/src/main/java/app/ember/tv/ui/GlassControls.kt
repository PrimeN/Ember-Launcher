package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Compact glass actions shared by full-page library and Live toolbars. */
@Composable
internal fun GlassControl(label: String, icon: ImageVector?, modifier: Modifier = Modifier,
    selected: Boolean = false, dropdown: Boolean = false, iconOnly: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick, modifier.widthIn(max = 260.dp).onFocusChanged { focused = it.isFocused }.semantics {
        contentDescription = label; this.selected = selected
    }, radius = TvTokens.Shape.Pill,
        container = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .32f) else Color.Transparent,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.glassSurface(RoundedCornerShape(TvTokens.Shape.Pill.dp), focused = focused)
            .heightIn(min = 38.dp).padding(horizontal = if (iconOnly) 10.dp else 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = if (focused) OnPillColor else Fg
            if (icon != null) MaterialGlyph(icon, color, 18.dp)
            if (!iconOnly) Text(label, Modifier.weight(1f, fill = false), color = color, fontSize = TvTokens.Type.CompactBody.sp,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (dropdown) MaterialGlyph(Icons.Outlined.ExpandMore, color, 16.dp)
        }
    }
}
