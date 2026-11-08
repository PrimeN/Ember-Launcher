package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.iptv.*
import coil.compose.AsyncImage

/** The selected programme's static artwork and information; selection never starts a stream. */
@Composable
internal fun LiveProgrammePanel(channel: Channel?, programme: Programme?, clock: Long, source: String,
    guideMessage: String, compact: Boolean = false, browsingTime: Long = clock,
    favourite: Boolean = false, onWatch: (() -> Unit)? = null, onFavourite: (() -> Unit)? = null) {
    val large = LocalDensity.current.fontScale > 1.25f
    val image = programme?.image
    var imageFailed by remember(image) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showArtwork = maxWidth >= 420.dp
        Row(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(TvTokens.Shape.Panel.dp), panel = true)
            .padding(12.dp).testTag("live_programme_panel"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (channel == null) {
                    Text("Your channel guide", color = Fg, fontSize = 22.sp, modifier = Modifier.semantics { heading() })
                } else {
                    Text(listOf(source, channel.name).filter(String::isNotBlank).distinct().joinToString(" · "),
                        color = Mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(programme?.title ?: channel.name, color = Fg, fontSize = if (compact || large) 22.sp else 28.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    val status = when {
                        programme == null -> if (browsingTime > clock + 60_000) "No scheduled programme" else "Live channel"
                        clock in programme.start until programme.stop -> "On now"
                        programme.start > clock -> "Upcoming"
                        else -> "Earlier"
                    }
                    Text(listOfNotNull(programme?.let { timeOf(it.start) + "–" + timeOf(it.stop) }, programme?.rating,
                        programme?.season?.let { "S$it" + (programme.episode?.let { e -> " E$e" } ?: "") },
                        programme?.subtitle, LivePresentation.remainingMinutes(programme, clock)?.let { "$it min left" }, status).joinToString(" · "),
                        color = Mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Two lines are kept even without a summary, so the panel (and the guide below it)
                    // doesn't jump while moving between channels (#123).
                    if (!compact && !large) Text(LivePresentation.summary(programme, guideMessage).orEmpty(), color = Mute,
                        fontSize = 13.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("live_programme_summary"))
                    if (onWatch != null || onFavourite != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onWatch != null) GlassControl("Watch live", Icons.Outlined.PlayArrow,
                            Modifier.testTag("live_watch"), onClick = onWatch)
                        if (onFavourite != null) GlassControl(if (favourite) "Remove favourite" else "Add favourite",
                            if (favourite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            Modifier.testTag("live_favourite"), selected = favourite, iconOnly = true, onClick = onFavourite)
                    }
                }
            }
            if (showArtwork && !large && image != null && !imageFailed) AsyncImage(image, null,
                Modifier.width(if (compact) 150.dp else 210.dp).height(if (compact) 94.dp else 120.dp).clip(RoundedCornerShape(TvTokens.Shape.Small.dp)),
                contentScale = ContentScale.Crop, onError = { imageFailed = true })
        }
    }
}
