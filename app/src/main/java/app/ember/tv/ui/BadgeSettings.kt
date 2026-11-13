package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.data.BadgeEditor
import app.ember.tv.data.BadgeFilter
import app.ember.tv.data.Badges

/** Where a badge is drawn in previews: a dark strip like the source list. */
private val Stage = Color(0xFF16171B)

/** Samples on the option cards: the common formats when the pack has them. */
internal object BadgeSamples {
    private val common = Regex("(?i)(4k|2160|dolby ?vision|\\bdv\\b|hdr|atmos|7\\.1|remux)")
    fun pick(list: List<BadgeFilter>, count: Int = 3): List<BadgeFilter> {
        val usable = list.filter { it.isEnabled }
        return (usable.filter { common.containsMatchIn(it.name) } + usable).distinct().take(count)
    }
    val basic = listOf("4K", "HDR10", "Atmos").map { BadgeFilter(name = it) }
}

/**
 * Settings > Sources > Badges (#237): the three options as cards that show their badges; the
 * Detailed categories; and, for your own badges, each pattern as a card opening one options dialog.
 */
@Composable
internal fun BadgesSection(vm: HomeViewModel) {
    val f = vm.sourceFormat
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var linking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var detailed by remember { mutableStateOf<List<BadgeFilter>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (!f.customBadgesRead) f.readCustomBadges()
        detailed = runCatching { BadgeSamples.pick(Badges.preview(context, Badges.MODERN)) }.getOrDefault(emptyList())
    }
    val custom = f.customBadges
    val modes = listOf(
        Triple(Badges.LEGACY, stringResource(R.string.ui_badges_legacy), "Plain text tags from each source's details."),
        Triple(Badges.MODERN, stringResource(R.string.ui_badges_modern), "Built-in pictures, kept on the TV. Works offline."),
        Triple(Badges.CUSTOM, stringResource(R.string.ui_badges_custom), "Your own patterns, colours and pictures."),
    )
    Section(stringResource(R.string.ui_badges), stringResource(R.string.ui_badges_note)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                modes.forEach { (mode, label, note) ->
                    val chosen = f.badgeMode == mode
                    val samples = when (mode) {
                        Badges.LEGACY -> BadgeSamples.basic
                        Badges.MODERN -> detailed
                        else -> BadgeSamples.pick(custom?.filters.orEmpty()).map { it.copy(imageURL = Badges.shownUrl(context, it.imageURL)) }
                    }
                    Focusable(onClick = { f.updateBadgeMode(mode) }, onLongClick = { preview = mode to label },
                        modifier = Modifier.width(248.dp).semantics { selected = chosen }.testTag("badge_mode_$mode"),
                        radius = TvTokens.Shape.Card, container = if (chosen) WashHi else PanelHi) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth().height(56.dp).background(Stage, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                if (samples.isEmpty()) Text(if (mode == Badges.CUSTOM) "No badges yet" else "…", color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
                                else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { samples.forEach { SourceBadge(it) } }
                            }
                            Text(if (chosen) "$label  ✓" else label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(note, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .72f), maxLines = 2, minLines = 2)
                        }
                    }
                }
            }
            ButtonRow(spacing = 8.dp) {
                val current = modes.first { it.first == f.badgeMode }
                Chip(stringResource(R.string.st_preview_badges, current.second)) { preview = current.first to current.second }
                val hidden = app.ember.tv.data.BadgeChoices.count(f.badgeMode, f.badgesOff)
                if (f.badgeMode != Badges.LEGACY && hidden > 0) Chip(stringResource(R.string.st_show_all_hidden_badges, hidden)) { f.showAllBadges(f.badgeMode) }
            }
            if (f.badgeMode == Badges.MODERN) {
                Text(stringResource(R.string.tx_detailed_categories), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                ButtonRow(spacing = 8.dp) {
                    app.ember.tv.data.DetailedBadgeCategories.groups.forEach { (id, label) ->
                        val on = id !in f.detailedBadgeCategoriesOff
                        SwitchChip(label, on, Modifier.semantics { stateDescription = if (on) "On" else "Off" }) { f.toggleDetailedBadgeCategory(id) }
                    }
                }
                Note(stringResource(R.string.st_to_change_a_detailed_badge_s))
                Chip(stringResource(R.string.st_copy_to_my_badges_and_edit)) { copying = true }
            }
            if (f.badgeMode == Badges.CUSTOM) {
                // Like the reference: adding comes first, then every pattern as a card.
                Focusable(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().testTag("badge_add"), radius = TvTokens.Shape.Card, container = PanelHi,
                    focusedScale = TvTokens.Focus.Inline) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(30.dp).background(LocalContentColor.current.copy(alpha = .14f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Text("+", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.tx_add_a_pattern), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.tx_a_regular_expression_matched_against_each), fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .72f))
                        }
                    }
                }
                ButtonRow(spacing = 8.dp) {
                    Chip(stringResource(R.string.st_load_a_pack_from_a_link)) { linking = true }
                    Chip(stringResource(R.string.st_start_from_the_detailed_badges)) { copying = true }
                }
                val filters = custom?.filters.orEmpty()
                if (filters.isNotEmpty()) {
                    Note("${filters.size} ${if (filters.size == 1) "pattern" else "patterns"}${filters.count { !it.isEnabled }.takeIf { it > 0 }?.let { " · $it off" }.orEmpty()}. " +
                        stringResource(R.string.st_the_first_patterns_come_first_under))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filters.forEach { b -> BadgeCard(b) { editing = b.id } }
                    }
                } else if (f.customBadgesRead) Note(stringResource(R.string.st_no_patterns_yet_add_one_start))
            }
            (f.badgeNote ?: Badges.message)?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        }
    }
    preview?.let { (mode, label) -> BadgePreview(mode, label, hiddenCategories = f.detailedBadgeCategoriesOff, off = f.badgesOff, onToggle = { f.toggleBadge(mode, it) }) { preview = null } }
    if (linking) BadgeLinkDialog(vm) { linking = false }
    if (adding) BadgeAddDialog(vm) { adding = false }
    if (copying) ConfirmDestructive("Start from the Detailed badges?",
        if (custom?.filters.isNullOrEmpty()) "Your badges become a copy of the Detailed badges, which you can then edit."
        else "This replaces your ${custom!!.filters.size} custom patterns with a copy of the Detailed badges.",
        { copying = false }, "Copy") { f.copyDetailedBadges() }
    editing?.let { id -> BadgeOptionsDialog(vm, id) { editing = null } }
}

/** One pattern: its badge as drawn, its name and its expression. */
@Composable
private fun BadgeCard(b: BadgeFilter, onClick: () -> Unit) {
    val context = LocalContext.current
    val shown = remember(b) { b.copy(imageURL = Badges.shownUrl(context, b.imageURL)) }
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("badge_card"), radius = TvTokens.Shape.Card, container = PanelHi,
        focusedScale = TvTokens.Focus.Inline) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(132.dp).height(44.dp).background(Stage, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.alpha(if (b.isEnabled) 1f else .35f)) { SourceBadge(shown) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.name.ifBlank { "Untitled" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!b.isEnabled) Text(stringResource(R.string.tx_off), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LocalContentColor.current.copy(alpha = .7f))
                }
                Text(b.pattern, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = LocalContentColor.current.copy(alpha = .72f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Colour swatches, with an optional "none" choice first. */
@Composable
private fun Swatches(selected: String?, none: String?, onPick: (String?) -> Unit) {
    ButtonRow(spacing = 8.dp) {
        none?.let { label ->
            Focusable(onClick = { onPick(null) }, modifier = Modifier.height(34.dp).semantics { this.selected = selected == null }, radius = TvTokens.Shape.Pill,
                container = if (selected == null) WashHi else PanelHi, focusedScale = TvTokens.Focus.Control) {
                Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(if (selected == null) "$label  ✓" else label, fontSize = 13.sp) }
            }
        }
        BadgeEditor.swatches.forEach { hex ->
            val colour = Color(Badges.color(hex)!!)
            val on = selected.equals(hex, ignoreCase = true)
            Focusable(onClick = { onPick(hex) }, modifier = Modifier.size(34.dp).semantics { contentDescription = hex; this.selected = on },
                radius = TvTokens.Shape.Pill, container = colour, focusedContainer = colour, focusedScale = TvTokens.Focus.Control) {
                if (on) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("✓", color = if (colour.luminance() > .5f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) = Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

/**
 * #237: every option of one pattern in one place, with the badge drawn large as it changes. Each
 * change is saved and used at once.
 */
@Composable
private fun BadgeOptionsDialog(vm: HomeViewModel, id: String, close: () -> Unit) {
    val f = vm.sourceFormat
    val context = LocalContext.current
    val b = f.customBadges?.filters?.firstOrNull { it.id == id }
    if (b == null) { LaunchedEffect(Unit) { close() }; return }
    var name by remember(id) { mutableStateOf(b.name) }
    var pattern by remember(id) { mutableStateOf(b.pattern) }
    var image by remember(id) { mutableStateOf(b.imageURL.orEmpty()) }
    var sample by remember(id) { mutableStateOf("") }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun edit(change: (BadgeFilter) -> BadgeFilter) = f.editBadge(id, change)
    val shown = remember(b) { b.copy(imageURL = Badges.shownUrl(context, b.imageURL)) }
    val all = f.customBadges?.filters.orEmpty()
    MediaDialog(b.name.ifBlank { "Badge" }, close, resizeForKeyboard = true, wide = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(130.dp).background(Stage, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.graphicsLayer(scaleX = 2f, scaleY = 2f).alpha(if (b.isEnabled) 1f else .35f)) { SourceBadge(shown) }
                }
                Text(BadgeEditor.summary(b), color = Mute, fontSize = 13.sp)
                ToggleRow(stringResource(R.string.st_show_this_badge), null, b.isEnabled) { edit { it.copy(isEnabled = !it.isEnabled) } }
                MoveButton(b.name, all.indexOfFirst { it.id == id }, all.lastIndex) { step -> f.moveBadge(id, step) }
                OptionLabel("Try it")
                TvTextField(sample, { sample = it }, Modifier.fillMaxWidth(), placeholder = "A source name, e.g. Movie 2160p REMUX DV")
                if (sample.isNotBlank()) {
                    val hit = BadgeEditor.matches(pattern, sample)
                    Text(when (hit) { true -> "Matches: this badge shows."; false -> "No match."; null -> "The pattern cannot run." },
                        color = if (hit == true) Color(0xFF8FE3B0) else Mute, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Chip(stringResource(R.string.st_delete_this_badge), modifier = Modifier.testTag("badge_delete")) { deleting = true }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionLabel("Tag colour")
                Swatches(BadgeEditor.rgb(b.tagColor), "None") { rgb -> edit { BadgeEditor.tagColour(it, rgb) } }
                if (BadgeEditor.rgb(b.tagColor) != null) StepSlider("Background opacity", BadgeEditor.opacitySteps, BadgeEditor.nearest(BadgeEditor.opacitySteps, BadgeEditor.opacity(b.tagColor).toFloat(), 100),
                    { "$it%" }, width = 460.dp) { p -> edit { it.copy(tagColor = BadgeEditor.withOpacity(BadgeEditor.rgb(it.tagColor), p)) } }
                if (b.imageURL.isNullOrBlank()) {
                    OptionLabel("Text colour")
                    Swatches(BadgeEditor.rgb(b.textColor), "Default") { rgb -> edit { it.copy(textColor = rgb) } }
                } else {
                    OptionLabel("Image colour")
                    Swatches(BadgeEditor.rgb(b.imageTint), "Original") { rgb -> edit { it.copy(imageTint = rgb) } }
                }
                StepSlider("Corner radius", BadgeEditor.radiusSteps, BadgeEditor.nearest(BadgeEditor.radiusSteps, b.borderRadius, 6), { "$it" }, width = 460.dp) { r ->
                    edit { it.copy(borderRadius = r.toFloat()) }
                }
                val thickness = if (b.borderColor == null) 0 else BadgeEditor.nearest(BadgeEditor.borderSteps, b.borderWidth ?: 1f, 1)
                StepSlider("Border thickness", BadgeEditor.borderSteps, thickness, { if (it == 0) "None" else "$it" }, width = 460.dp) { w -> edit { BadgeEditor.border(it, w) } }
                if (thickness > 0) {
                    OptionLabel("Border colour")
                    Swatches(BadgeEditor.rgb(b.borderColor), null) { rgb -> edit { it.copy(borderColor = rgb) } }
                }
                OptionLabel("Image")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextField(image, { image = it }, Modifier.weight(1f), placeholder = "Picture link (https://…), or send one from the phone page")
                    Chip(stringResource(R.string.st_use)) { edit { it.copy(imageURL = image.trim().ifBlank { null }) } }
                    if (!b.imageURL.isNullOrBlank()) Chip(stringResource(R.string.cal_remove_clip)) { image = ""; edit { it.copy(imageURL = null) } }
                }
                OptionLabel("Pattern")
                TvTextField(pattern, { pattern = it; problem = null }, Modifier.fillMaxWidth(), placeholder = "Regular expression", singleLine = false, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(stringResource(R.string.st_save_pattern)) { problem = BadgeEditor.check(pattern); if (problem == null) edit { it.copy(pattern = pattern) } }
                    if (pattern != b.pattern) Chip(stringResource(R.string.st_undo)) { pattern = b.pattern; problem = null }
                }
                problem?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 13.sp) }
                OptionLabel("Name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextField(name, { name = it.take(60) }, Modifier.weight(1f), placeholder = "Badge name")
                    Chip(stringResource(R.string.ui_rename)) { if (name.isNotBlank()) edit { it.copy(name = name.trim()) } }
                }
            }
        }
    }
    if (deleting) ConfirmDestructive("Delete ${b.name.ifBlank { "this badge" }}?", "Its pattern, colours and picture are removed from your badges.",
        { deleting = false }) { f.deleteBadge(id); close() }
}

@Composable
private fun BadgeAddDialog(vm: HomeViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    MediaDialog("Add a pattern", close, resizeForKeyboard = true) {
        Note(stringResource(R.string.st_the_badge_shows_under_every_source))
        TvTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), placeholder = "Badge name, e.g. REMUX")
        TvTextField(pattern, { pattern = it; problem = null }, Modifier.fillMaxWidth(), placeholder = "Pattern, e.g. \\bREMUX\\b", fontSize = 14.sp)
        TvTextField(sample, { sample = it }, Modifier.fillMaxWidth(), placeholder = "Try it on a source name (optional)")
        if (sample.isNotBlank() && pattern.isNotBlank()) Text(when (BadgeEditor.matches(pattern, sample)) { true -> "Matches."; false -> "No match."; null -> "The pattern cannot run." }, color = Mute, fontSize = 13.sp)
        problem?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 13.sp) }
        ButtonRow { Chip(stringResource(R.string.tx_add)) { problem = vm.sourceFormat.addBadge(name, pattern); if (problem == null) close() }; Chip(stringResource(R.string.ui_cancel)) { close() } }
    }
}

@Composable
private fun BadgeLinkDialog(vm: HomeViewModel, close: () -> Unit) {
    var url by remember { mutableStateOf("") }
    MediaDialog("Load a badge pack", close, resizeForKeyboard = true) {
        Note(stringResource(R.string.ui_badges_custom_note))
        Text(stringResource(R.string.tx_this_replaces_your_custom_badges), color = Mute, fontSize = 13.sp)
        TvTextField(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "https://.../badges.json")
        ButtonRow { Chip(stringResource(R.string.ui_badges_load)) { if (url.isNotBlank()) { vm.sourceFormat.importBadges(url); close() } }; Chip(stringResource(R.string.ui_cancel)) { close() } }
    }
}
