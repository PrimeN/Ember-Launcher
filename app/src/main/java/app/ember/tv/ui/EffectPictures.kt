package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Pictures for Performance & effects (#223) and Startup & screensaver (#224). */
internal object EffectPictures {
    val groups = setOf("perf", "glass", "focusbg", "cardfocus", "start", "saver")
    /** Bars in the performance meter: more for richer images and effects. */
    fun bars(profile: String): Int = when (profile) { "low" -> 1; "medium" -> 2; "high" -> 3; "ultra" -> 4; else -> 0 }
}

/** Something colourful behind a panel, so glass styles have something to show through. */
@Composable
private fun Backdrop(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp))
    .background(Brush.linearGradient(listOf(Color(0xFFFF7A2F), Color(0xFF7B5CFF), Color(0xFF2EC4B6))))) {
    Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(30.dp, 14.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E88E5)))
    content()
}

@Composable
internal fun EffectPicture(picture: String, ink: Color) {
    val soft = ink.copy(alpha = .4f); val strong = ink.copy(alpha = .9f); val faint = ink.copy(alpha = .22f)
    fun Modifier.block(color: Color, corner: Int = 3) = clip(RoundedCornerShape(corner.dp)).background(color)
    val id = picture.substringAfter('-')
    when (picture.substringBefore('-')) {
        // Performance: a meter of bars, Auto choosing for you, Custom as sliders.
        "perf" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (id) {
                "auto" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    (1..4).forEach { n -> Box(Modifier.width(9.dp).height((8 + n * 9).dp).block(if (n <= 2) strong else faint)) }
                    Box(Modifier.padding(start = 6.dp).size(18.dp).clip(CircleShape).background(soft), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(strong))
                    }
                }
                "custom" -> Column(Modifier.width(80.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf(.3f, .7f, .5f).forEach { at ->
                        Box(Modifier.fillMaxWidth().height(3.dp).block(soft)) { Box(Modifier.fillMaxWidth(at).fillMaxHeight().block(strong)) }
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    (1..4).forEach { n -> Box(Modifier.width(11.dp).height((8 + n * 9).dp).block(if (n <= EffectPictures.bars(id)) strong else faint)) }
                }
            }
        }
        // Glass: the same panel over a colourful page, from solid to see-through.
        "glass" -> Backdrop {
            val panel = Modifier.align(Alignment.Center).fillMaxWidth(.7f).fillMaxHeight(.6f).clip(RoundedCornerShape(8.dp))
            when (id) {
                "solid" -> Box(panel.background(Color(0xFF2A2E36)))
                "tint" -> Box(panel.background(Color(0xFF1C1F26).copy(alpha = .62f)))
                "cached" -> Box(panel.background(Color.White.copy(alpha = .38f)).background(Color(0xFF1C1F26).copy(alpha = .3f)))
                "liquid" -> Box(panel.background(Color.White.copy(alpha = .18f)).border(1.5.dp, Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = .1f))), RoundedCornerShape(8.dp)))
                else -> Row(panel) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1C1F26).copy(alpha = .62f)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.White.copy(alpha = .38f)))
                }
            }
            Box(Modifier.align(Alignment.Center).width(40.dp).height(5.dp).block(Color.White))
        }
        // Focused backgrounds: the page behind a focused card.
        "focusbg" -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(
            when (id) {
                "colors" -> Brush.verticalGradient(listOf(Color(0xFF7A3B2E), Color(0xFF1F232C)))
                "glow" -> Brush.radialGradient(listOf(Color(0xFFFF7A2F).copy(alpha = .7f), Color(0xFF1F232C)), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 160f)
                else -> Brush.verticalGradient(listOf(Color(0xFF1F232C), Color(0xFF1F232C)))
            })) {
            if (id == "artwork") Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF1F232C), Color(0xFF3E5A7A), Color(0xFF8BA6C4)))))
            Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(26.dp, 34.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE07A5F)).border(1.5.dp, Color.White, RoundedCornerShape(3.dp)))
                repeat(3) { Box(Modifier.size(26.dp, 34.dp).block(Color.White.copy(alpha = .25f))) }
            }
        }
        // Card focus: one card focused among others.
        "cardfocus" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp, 38.dp).block(soft))
            val card = Modifier.size(26.dp, 38.dp)
            when (id) {
                "lift" -> Box(card.scale(1.18f).shadow(8.dp, RoundedCornerShape(3.dp)).block(strong))
                "glow" -> Box(Modifier.size(38.dp, 50.dp).clip(RoundedCornerShape(8.dp)).background(Brush.radialGradient(listOf(Color(0xFFFF7A2F).copy(alpha = .8f), Color.Transparent))), contentAlignment = Alignment.Center) { Box(card.block(strong)) }
                "pulse" -> Box(Modifier.size(36.dp, 48.dp).border(1.dp, strong.copy(alpha = .5f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Box(card.border(2.dp, strong, RoundedCornerShape(3.dp)).padding(3.dp).block(soft)) }
                "subtle" -> Box(card.scale(1.06f).border(2.dp, strong, RoundedCornerShape(3.dp)).padding(2.dp).block(soft))
                else -> Box(card.block(soft))
            }
            Box(Modifier.size(26.dp, 38.dp).block(soft))
        }
        // Startup: where the TV opens.
        "start" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (id) {
                "home" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f).block(soft, 4))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(4) { Box(Modifier.weight(1f).height(12.dp).block(soft)) } }
                }
                "app" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).block(soft, 6)); Box(Modifier.size(34.dp).block(strong, 9)); Box(Modifier.size(22.dp).block(soft, 6))
                }
                "input" -> Box(Modifier.size(56.dp, 34.dp).border(2.dp, strong, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp, 8.dp).block(strong, 2))
                }
                else -> Box(Modifier.size(70.dp, 44.dp).block(soft, 4), contentAlignment = Alignment.BottomCenter) {
                    Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { i -> Box(Modifier.size(4.dp).clip(CircleShape).background(if (i == 0) strong else faint)) } }
                }
            }
        }
        // Screensaver sources.
        "saver" -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(
            when (id) {
                "space" -> Brush.radialGradient(listOf(Color(0xFF2B3A67), Color(0xFF05070D)))
                "art" -> Brush.verticalGradient(listOf(Color(0xFF6B4F2A), Color(0xFF2E2416)))
                "photos" -> Brush.verticalGradient(listOf(Color(0xFF4F7CAC), Color(0xFF9FC5A8)))
                else -> Brush.horizontalGradient(listOf(Color(0xFF1F232C), Color(0xFF5B4B8A), Color(0xFFE07A5F)))
            })) {
            when (id) {
                "space" -> {
                    Box(Modifier.align(Alignment.Center).size(26.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFC27A), Color(0xFFB0532E)))))
                    listOf(8 to 8, 90 to 14, 30 to 46, 120 to 50, 60 to 6).forEach { (x, y) -> Box(Modifier.offset(x.dp, y.dp).size(2.dp).clip(CircleShape).background(Color.White)) }
                }
                "art" -> Box(Modifier.align(Alignment.Center).size(70.dp, 46.dp).border(4.dp, Color(0xFFD4AF37), RoundedCornerShape(2.dp)).padding(4.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF8FB3D9), Color(0xFF6E8B3D)))))
                "photos" -> {
                    Box(Modifier.align(Alignment.Center).offset((-8).dp, (-4).dp).size(48.dp, 36.dp).border(2.dp, Color.White, RoundedCornerShape(2.dp)).background(Color(0xFFE6B655).copy(alpha = .6f)))
                    Box(Modifier.align(Alignment.Center).offset(8.dp, 4.dp).size(48.dp, 36.dp).border(2.dp, Color.White, RoundedCornerShape(2.dp)).background(Color(0xFF6FA8DC)))
                }
                else -> Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(46.dp, 6.dp).block(Color.White))
            }
        }
    }
}
