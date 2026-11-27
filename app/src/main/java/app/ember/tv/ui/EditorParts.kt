package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/*
 * #468: the pieces every editor shares, so collections, folders and widgets look and move alike:
 * steps across the top, a heading with a line under it, picture cards for choices, and a footer
 * with Back on the left, what's missing in the middle and the filled main action on the right.
 */

/** A part of an editor: its title, then one line on what it's for. */
@Composable
internal fun EditorHeading(title: String, intro: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        if (!intro.isNullOrBlank()) Text(intro, fontSize = 13.sp, color = Mute)
    }
}

/** A smaller heading inside a part. */
@Composable
internal fun EditorLabel(text: String) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Fg)

/** The steps across the top: numbered, the current one filled, finished ones ticked. */
@Composable
internal fun EditorSteps(steps: List<Pair<String, String>>, current: String, tagPrefix: String,
    done: (String) -> Boolean = { false }, badge: (String) -> String? = { null }, go: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (id, name) ->
            val now = id == current
            val ticked = !now && done(id)
            if (i > 0) Box(Modifier.width(28.dp).height(2.dp).background(accent.copy(alpha = .6f)))
            Focusable(onClick = { go(id) }, modifier = Modifier.semantics { this.selected = now }.testTag(tagPrefix + id), radius = TvTokens.Shape.Pill,
                container = if (now) WashHi else Color.Transparent, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false,
                focusedScale = TvTokens.Focus.Control) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(26.dp).background(if (now || ticked) accent else Wash, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        if (ticked) MaterialGlyph(Icons.Outlined.Check, MaterialTheme.colorScheme.onPrimary, 16.dp)
                        else Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (now) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current)
                    }
                    Text(name + (badge(id)?.let { " · $it" } ?: ""), fontSize = 15.sp, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/** The main action: filled with the accent when it can be used, dimmed when it can't. */
@Composable
internal fun PrimaryAction(label: String, ready: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = modifier, radius = TvTokens.Shape.Pill, container = if (ready) accent else Wash,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control) {
        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (ready) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current.copy(alpha = .6f))
    }
}

/** Back or Cancel on the left, what's missing in the middle, the actions on the right. */
@Composable
internal fun EditorFooter(back: String, onBack: () -> Unit, message: String?, warn: Boolean = true, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(back) { onBack() }
        Text(message.orEmpty(), Modifier.weight(1f), color = if (message != null && warn) Color(0xFFFFB86B) else Mute,
            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/**
 * A choice shown as a card: a picture or icon, its name and a line about it, ticked when chosen.
 * [picture] draws inside the card's top part, for a choice that's easier seen than read.
 */
@Composable
internal fun ChoiceCard(name: String, about: String?, selected: Boolean, modifier: Modifier = Modifier, width: Dp = 214.dp,
    icon: ImageVector? = null, picture: (@Composable BoxScope.() -> Unit)? = null, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = modifier.width(width).semantics { this.selected = selected }
        .then(if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(TvTokens.Shape.Card.dp)) else Modifier),
        radius = TvTokens.Shape.Card, container = if (selected) WashHi else PanelHi, focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.Card) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (picture != null) Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { picture() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (icon != null) Box(Modifier.size(30.dp).background(accent.copy(alpha = .2f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        MaterialGlyph(icon, accent, 18.dp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!about.isNullOrBlank()) Text(about, fontSize = 12.sp, lineHeight = 14.sp, color = LocalContentColor.current.copy(alpha = .7f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).background(accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                MaterialGlyph(Icons.Outlined.Check, MaterialTheme.colorScheme.onPrimary, 14.dp)
            }
        }
    }
}

/** A switch as a full row with a line under its name, as on the Settings pages. */
@Composable
internal fun EditorSwitch(name: String, about: String?, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().semantics { this.selected = on }, radius = TvTokens.Shape.Card,
        container = PanelHi, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Inline) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp)
                if (!about.isNullOrBlank()) Text(about, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .7f))
            }
            SwitchKnob(on)
        }
    }
}

/** The knob of a switch row: the accent when on. */
@Composable
internal fun SwitchKnob(on: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.width(44.dp).height(24.dp).background(if (on) accent else Mute.copy(alpha = .4f), RoundedCornerShape(50)).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(18.dp).background(Color.White, RoundedCornerShape(50)))
    }
}
