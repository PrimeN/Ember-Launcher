package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Small pictures for settings that change how Ember looks (#203): each choice shows a diagram of
 * the result instead of only its name. Diagrams are drawn in the tile's own ink, so they read on
 * every theme and when focused; the two theme pictures use the real Night and Day backgrounds.
 */
internal object SettingPictures {
    /** The picture for [option] of the setting [group]; unknown options fall back to a plain panel. */
    fun of(group: String, option: String): String = "$group-$option"
    val known = setOf(
        "ui-modern", "ui-light", "ui-ember_x", "theme-night", "theme-day", "theme-auto", "menu-top", "menu-side",
        "nav-ember", "nav-ember_collapsed", "nav-cinema_bar", "page-modern", "page-ember_x", "apps-banner", "apps-circle",
        "density-comfortable", "density-standard", "density-dense", "apps-square",
        "player-basic", "player-modern", "player-ember_x", "player-streaming",
        "info-start", "info-ask",
    ) + UiSizes.LABELS.flatMap { (id, _) -> listOf("uisize-$id", "textsize-$id") }
    /** How many cards the density pictures show, and the gap between them in dp: looser to tighter. */
    fun density(option: String): Pair<Int, Int> = when (option) { "comfortable" -> 3 to 9; "dense" -> 5 to 3; else -> 4 to 6 }
}

/**
 * A player layout in miniature (#207): Ember is a solid panel at the foot of the picture, Glass the
 * same panel frosted, Basic a cinema bar with the controls centred, Streaming small icons at the top
 * with the title opposite and a thin bar below.
 */
@Composable
private fun PlayerPicture(style: String, ink: Color) {
    val white = Color.White; val dim = Color.White.copy(alpha = .5f)
    fun Modifier.block(color: Color, corner: Int = 2) = clip(RoundedCornerShape(corner.dp)).background(color)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3A4150), Color(0xFF151820)))).padding(5.dp)) {
        when (style) {
            app.ember.tv.player.PlayerInterfaces.STREAMING -> {
                Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { i -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == 0) white else dim)) } }
                Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) { Box(Modifier.width(30.dp).height(4.dp).block(white)); Box(Modifier.width(20.dp).height(3.dp).block(dim)) }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(30.dp).height(7.dp).block(white))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(white))
                        Box(Modifier.weight(1f).height(2.dp).block(dim)) }
                }
            }
            app.ember.tv.player.PlayerInterfaces.EMBER_X -> Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(3.dp).block(dim)) { Box(Modifier.fillMaxWidth(.4f).fillMaxHeight().block(white)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dim)); Box(Modifier.size(11.dp).clip(CircleShape).background(white)); Box(Modifier.size(6.dp).clip(CircleShape).background(dim))
                }
            }
            else -> {
                val glass = style == app.ember.tv.player.PlayerInterfaces.MODERN
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                    .block(if (glass) Color.White.copy(alpha = .22f) else Color(0xE6101012), if (glass) 6 else 2).padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(34.dp).height(4.dp).block(white))
                    Box(Modifier.fillMaxWidth().height(2.dp).block(dim)) { Box(Modifier.fillMaxWidth(.55f).fillMaxHeight().block(white)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(6) { i -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == 1) white else dim)) } }
                }
            }
        }
    }
}

/** A row of picture tiles for one setting; the selected one is filled and ticked. */
@Composable
internal fun PictureChoice(group: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    ButtonRow(Modifier.padding(vertical = 4.dp)) {
        options.forEach { (id, label) ->
            val picture = SettingPictures.of(group, id)
            Focusable(onClick = { onPick(id) }, modifier = Modifier.width(150.dp).semantics { this.selected = selected == id }.testTag("picture_$picture"),
                radius = TvTokens.Shape.Card, container = if (selected == id) WashHi else PanelHi) {
                val ink = androidx.tv.material3.LocalContentColor.current
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingPicture(picture, ink)
                    Text(if (selected == id) "$label  ✓" else label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SettingPicture(picture: String, ink: Color) {
    val soft = ink.copy(alpha = .4f); val strong = ink.copy(alpha = .9f); val faint = ink.copy(alpha = .22f)
    fun Modifier.block(color: Color, corner: Int = 3) = clip(RoundedCornerShape(corner.dp)).background(color)
    @Composable fun Cards(count: Int, gap: Int = 4, height: Int = 16) = Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
        repeat(count) { Box(Modifier.weight(1f).height(height.dp).block(soft)) }
    }
    @Composable fun TopBar(pills: Int) = Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(pills) { i -> Box(Modifier.width(if (i == 0) 16.dp else 11.dp).height(5.dp).block(if (i == 0) strong else soft, 3)) }
    }
    @Composable fun Rail() = Column(Modifier.fillMaxHeight().width(8.dp).block(faint, 2).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { i -> Box(Modifier.size(4.dp).clip(CircleShape).background(if (i == 0) strong else soft)) }
    }
    @Composable fun Page() = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).block(soft, 4))
        Cards(4)
    }
    Box(Modifier.fillMaxWidth().height(74.dp).block(ink.copy(alpha = .1f), 8).padding(7.dp)) {
        when (picture) {
            // Interface: Ember is a banner over rows; Light is a grid of apps; X is a rail beside a dark banner.
            "ui-modern" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) { TopBar(4); Box(Modifier.fillMaxWidth().weight(1f).block(soft, 4)); Cards(4, height = 12) }
            "ui-light" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.align(Alignment.End).width(22.dp).height(5.dp).block(strong))
                repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(5) { Box(Modifier.weight(1f).height(20.dp).block(soft, 5)) } } }
            }
            "ui-ember_x", "page-ember_x" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (picture == "ui-ember_x") { Rail(); Page() } else {
                    // The media page with its panel on the right.
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                        Box(Modifier.width(46.dp).height(7.dp).block(strong)); Box(Modifier.width(60.dp).height(4.dp).block(soft)); Box(Modifier.width(26.dp).height(9.dp).block(strong, 5))
                    }
                    Column(Modifier.width(44.dp).fillMaxHeight().block(faint, 4).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { repeat(5) { Box(Modifier.fillMaxWidth().height(7.dp).block(soft)) } }
                }
            }
            // The media page: details at the left over its artwork, rows beneath.
            "page-modern" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                Box(Modifier.width(52.dp).height(7.dp).block(strong)); Box(Modifier.width(70.dp).height(4.dp).block(soft))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Box(Modifier.width(26.dp).height(9.dp).block(strong, 5)); Box(Modifier.width(18.dp).height(9.dp).block(soft, 5)) }
                Cards(5, height = 12)
            }
            // Theme: the real backgrounds, so Night and Day look like themselves.
            "theme-night", "theme-day", "theme-auto" -> Row(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp))) {
                @Composable fun Half(night: Boolean) = Column(Modifier.weight(1f).fillMaxHeight().background(if (night) Color(0xFF1F232C) else Color(0xFFF1F3F7)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    val line = if (night) Color(0xFFE6EAED) else Color(0xFF1F2328)
                    Box(Modifier.fillMaxWidth(.7f).height(5.dp).block(line)); Box(Modifier.fillMaxWidth().height(14.dp).block(line.copy(alpha = .35f)))
                }
                if (picture != "theme-day") Half(true)
                if (picture != "theme-night") Half(false)
            }
            // Menu: along the top, or down the side.
            "menu-top", "nav-ember" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) { TopBar(5); Page() }
            "menu-side" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(5.dp)) { Rail(); Page() }
            // Navbar styles: the full bar, one pill that opens, or a dark strip across the top.
            "nav-ember_collapsed" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) { TopBar(1); Page() }
            "nav-cinema_bar" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth().height(9.dp).block(Color.Black.copy(alpha = .75f), 2), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    repeat(4) { i -> Box(Modifier.width(9.dp).height(3.dp).block(if (i == 0) Color.White else Color.White.copy(alpha = .5f))) }
                }
                Page()
            }
            // App icons: banners or circles.
            "apps-banner" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) { repeat(3) { Box(Modifier.weight(1f).height(24.dp).block(soft, 5)) } }
            "apps-square" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { repeat(4) { Box(Modifier.size(24.dp).block(soft, 6)) } }
            // Player interfaces (#207), over a dark frame of video.
            "player-basic", "player-modern", "player-ember_x", "player-streaming" -> PlayerPicture(picture.removePrefix("player-"), ink)
            "apps-circle" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { repeat(4) { Box(Modifier.size(24.dp).clip(CircleShape).background(soft)) } }
            "density-comfortable", "density-standard", "density-dense" -> {
                val (count, gap) = SettingPictures.density(picture.removePrefix("density-"))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) { repeat(count) { Box(Modifier.weight(1f).height(46.dp).block(soft)) } }
            }
            // Playback info (#218): a card over the picture when playback starts, or just the picture.
            "info-start", "info-ask" -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3A4150), Color(0xFF151820))))) {
                if (picture == "info-start") Column(Modifier.align(Alignment.TopStart).padding(6.dp).width(62.dp).block(Color.Black.copy(alpha = .6f), 4).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(44.dp).height(5.dp).block(Color.White)); Box(Modifier.width(36.dp).height(3.dp).block(Color.White.copy(alpha = .6f))); Box(Modifier.width(50.dp).height(3.dp).block(Color.White.copy(alpha = .6f)))
                } else Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(12.dp).clip(CircleShape).background(Color.White.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                    // An "i" drawn as a dot over a bar: text this small would break the 12 sp rule (#37).
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Box(Modifier.size(2.dp).clip(CircleShape).background(Color.White)); Box(Modifier.width(2.dp).height(4.dp).background(Color.White))
                    }
                }
            }
            // #215: the same panel at each interface size, and "Aa" at each text size.
            else -> if (picture.startsWith("uisize-")) SizePicture(UiSizes.screen(picture.removePrefix("uisize-")) / UiSizes.screen("xlarge"), ink)
                else if (picture.startsWith("textsize-")) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aa", fontSize = (30f * UiSizes.text(picture.removePrefix("textsize-"))).sp, fontWeight = FontWeight.SemiBold, color = strong, maxLines = 1)
                }
                else Box(Modifier.fillMaxSize().block(soft, 4))
        }
    }
}

/** A banner over a row of cards, drawn at [scale] of the largest interface size (#215). */
@Composable
private fun SizePicture(scale: Float, ink: Color) {
    val soft = ink.copy(alpha = .4f); val strong = ink.copy(alpha = .9f)
    fun Modifier.block(color: Color, corner: Int = 3) = clip(RoundedCornerShape(corner.dp)).background(color)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Column(Modifier.fillMaxWidth(scale).fillMaxHeight(scale), verticalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
            Box(Modifier.fillMaxWidth(.5f).height((7 * scale).dp).block(strong))
            Box(Modifier.fillMaxWidth().weight(1f).block(soft, 4))
            Row(horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)) { repeat(3) { Box(Modifier.weight(1f).height((14 * scale).dp).block(soft)) } }
        }
    }
}
