package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.SubtitleConfig
import app.ember.tv.data.SubtitleFonts

/** How subtitle settings draw in Settings (#219), matching what the players do. */
internal object SubtitleLooks {
    val sizes = listOf("small" to "Small", "normal" to "Normal", "large" to "Large", "xlarge" to "Extra large")
    val backgrounds = listOf("outline" to "Outline", "shadow" to "Shadow", "box" to "Dark box")
    val colors = listOf("white" to "White", "yellow" to "Yellow")
    val positions = listOf("low" to "Low", "normal" to "Normal", "high" to "Higher")
    fun scale(size: String): Float = when (size) { "small" -> .8f; "large" -> 1.25f; "xlarge" -> 1.5f; else -> 1f }
    fun lift(position: String): Float = when (position) { "low" -> .04f; "high" -> .22f; else -> .1f }
    fun color(c: String): Color = if (c == "yellow") Color(0xFFFFE650) else Color.White
}

/** The font a subtitle choice draws with, here in Settings. */
@Composable
internal fun subtitleFamily(font: String): FontFamily {
    val context = LocalContext.current
    return remember(font, SubtitleFonts.hasCustom(context)) {
        when (SubtitleFonts.normalize(font)) {
            SubtitleFonts.DEFAULT -> FontFamily.SansSerif
            else -> FontFamily(SubtitleFonts.typeface(context, font))
        }
    }
}

/** A line of subtitle as the player would show it, over a dark frame of video. */
@Composable
internal fun SubtitleSample(c: SubtitleConfig, modifier: Modifier, text: String = "Subtitles look like this", base: Float = 15f) {
    val family = subtitleFamily(c.font)
    val bold = if (SubtitleFonts.normalize(c.font) == SubtitleFonts.DEFAULT) FontWeight.Bold else FontWeight.Medium
    val size = (base * SubtitleLooks.scale(c.size)).sp
    val fill = SubtitleLooks.color(c.color)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(listOf(Color(0xFF46506A), Color(0xFF1A1E28))))) {
        val lift = maxHeight * SubtitleLooks.lift(c.position)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = lift, start = 6.dp, end = 6.dp)) {
            val style = TextStyle(fontFamily = family, fontWeight = bold, fontSize = size, lineHeight = size * 1.15f, textAlign = TextAlign.Center)
            when (c.background) {
                "box" -> Text(text, Modifier.background(Color(0xBE000000)).padding(horizontal = 6.dp, vertical = 2.dp), style = style, color = fill, maxLines = 2, overflow = TextOverflow.Ellipsis)
                "shadow" -> Text(text, style = style.copy(shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)), color = fill, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else -> Box {
                    Text(text, style = style.copy(drawStyle = Stroke(width = 4f)), color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(text, style = style, color = fill, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One subtitle setting as samples (#219): each choice drawn on top of the rest of your look. */
@Composable
internal fun SubtitleChoice(title: String, options: List<Pair<String, String>>, selected: String, c: SubtitleConfig, apply: (SubtitleConfig, String) -> SubtitleConfig, onPick: (String) -> Unit) {
    Text(title, fontSize = 15.sp)
    ButtonRow(Modifier.padding(vertical = 4.dp)) {
        options.forEach { (id, label) ->
            Focusable(onClick = { onPick(id) }, modifier = Modifier.width(170.dp).semantics { this.selected = selected == id }.testTag("subtitle_${title.lowercase()}_$id"),
                radius = TvTokens.Shape.Card, container = if (selected == id) WashHi else PanelHi) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubtitleSample(apply(c, id), Modifier.fillMaxWidth().height(74.dp), text = "Subtitles", base = 13f)
                    Text(if (selected == id) "$label  ✓" else label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The live preview at the top of the look settings. */
@Composable
internal fun SubtitlePreview(c: SubtitleConfig, width: Dp = 560.dp) {
    SubtitleSample(c, Modifier.width(width).height(150.dp).testTag("subtitle_preview"), base = 18f)
}
