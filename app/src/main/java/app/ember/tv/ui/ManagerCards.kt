package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text

/**
 * #239: one card for everything listed under Widgets & collections, so widgets and collections
 * look and behave the same: a picture, a title, a line or two, and an optional control beside it.
 */
@Composable
internal fun ManagerCard(
    title: String, lines: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "manager_card",
    trailing: (@Composable () -> Unit)? = null, extra: (@Composable RowScope.() -> Unit)? = null, picture: @Composable BoxScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Focusable(onClick = onClick, modifier = Modifier.weight(1f).testTag(tag), radius = TvTokens.Shape.Card, container = PanelHi, focusedScale = TvTokens.Focus.Inline) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(ManagerCards.PICTURE_W).height(ManagerCards.PICTURE_H).background(WashHi, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center, content = picture)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lines.filter { it.isNotBlank() }.forEach { Text(it, color = LocalContentColor.current.copy(alpha = .7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                extra?.invoke(this)
            }
        }
        trailing?.invoke()
    }
}

internal object ManagerCards {
    val PICTURE_W = 112.dp
    val PICTURE_H = 63.dp
    /** "4 on Home · 12 off": the count beside both toolbars. */
    fun counts(on: Int, off: Int) = "$on on Home" + if (off > 0) " · $off off" else ""
}

/** A header that opens and closes a group below it (#239's turned-off widgets). */
@Composable
internal fun FoldHeader(title: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth(), radius = TvTokens.Shape.Card, container = PanelHi, focusedScale = TvTokens.Focus.Inline) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (open) "▾" else "▸", fontSize = 16.sp)
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = .7f))
        }
    }
}
