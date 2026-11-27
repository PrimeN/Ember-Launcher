package app.ember.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.SourceRules

/**
 * #471: Settings › Watching › Choosing a stream › Hide sources. Each group is a row of chips;
 * a chip that's on hides those sources. Your media servers are never hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HideSourcesSection(vm: HomeViewModel) {
    val addonNames = vm.addonInfo.values.filter { "stream" in it.provides }.map { it.name }.distinct().sorted()
    HideSourcesSection(vm.sourceFormat.sourceRules, addonNames) { f -> vm.sourceFormat.updateSourceRules(f) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HideSourcesSection(r: SourceRules, addonNames: List<String>, update: ((SourceRules) -> SourceRules) -> Unit) {
    fun change(f: (SourceRules) -> SourceRules) = update(f)
    val summary = if (!r.active) "Nothing is hidden. Turn on what you never want to see; a title screen can still show what's filtered."
        else "${r.count} ${if (r.count == 1) "filter" else "filters"} on. A title screen shows how many sources they hid, and can show them."
    Section("Hide sources", summary, id = "hide-sources") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ToggleRow("Hide un-cached and torrents", "Sources that have to download before they play.", r.uncached) { change { it.copy(uncached = !it.uncached) } }
            ToggleRow("Hide CAM and TS copies", "Recorded in a cinema: poor picture and sound.", r.cam) { change { it.copy(cam = !it.cam) } }
            FilterChips("Picture", SourceRules.QUALITIES.map { it to qualityName(it) }, r.qualities, "hide_quality") { q -> change { it.copy(qualities = it.toggle(it.qualities, q)) } }
            FilterChips("HDR", SourceRules.HDR.map { it to when (it) { "DV" -> "Dolby Vision"; "HDR" -> "HDR10 / HDR10+"; else -> "SDR" } }, r.hdr, "hide_hdr") { h -> change { it.copy(hdr = it.toggle(it.hdr, h)) } }
            FilterChips("Codec", SourceRules.CODECS.map { it to it }, r.codecs, "hide_codec") { c -> change { it.copy(codecs = it.toggle(it.codecs, c)) } }
            FilterChips("Release", SourceRules.RELEASES.map { it to it }, r.releases, "hide_release") { v -> change { it.copy(releases = it.toggle(it.releases, v)) } }
            SizeChips("Bigger than", SourceRules.MAX_SIZES, r.maxSizeGb, "hide_max") { v -> change { it.copy(maxSizeGb = v) } }
            SizeChips("Smaller than", SourceRules.MIN_SIZES, r.minSizeGb, "hide_min") { v -> change { it.copy(minSizeGb = v) } }
            val shownAddons = (addonNames + r.addons).distinct()
            if (shownAddons.isNotEmpty()) FilterChips("Addons", shownAddons.map { it to it }, r.addons, "hide_addon") { a -> change { it.copy(addons = it.toggle(it.addons, a)) } }
            WordFilters(r.words, onAdd = { w -> change { it.copy(words = SourceRules.addWord(it.words, w)) } },
                onRemove = { w -> change { it.copy(words = it.words - w) } })
            if (r.active) Chip("Show everything again", modifier = Modifier.testTag("hide_clear")) { change { SourceRules() } }
        }
    }
}

private fun qualityName(q: String) = when (q) { "4K" -> "4K"; "FHD" -> "1080p"; "HD" -> "720p"; else -> "SD and unknown" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(label: String, options: List<Pair<String, String>>, on: Set<String>, tag: String, toggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label + if (on.isNotEmpty()) " · ${on.size} hidden" else "", color = Mute, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (id, name) ->
                Chip(if (id in on) "$name · hidden" else name, selected = id in on, modifier = Modifier.testTag("${tag}_$id"), maxLines = 1) { toggle(id) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeChips(label: String, sizes: List<Int>, value: Int, tag: String, pick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Mute, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sizes.forEach { s -> Chip(if (s == 0) "Don't hide" else "$s GB", selected = value == s, modifier = Modifier.testTag("${tag}_$s"), maxLines = 1) { pick(s) } }
        }
    }
}

/** Words and tags: a field to type one, the common ones as suggestions, and those on to remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordFilters(words: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Words and tags · a release group, a language, a tag like HDTS. Whole words, any case.", color = Mute, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(draft, { draft = it.take(40) }, Modifier.width(320.dp).testTag("hide_word_field"), placeholder = "Type a word to hide",
                onSubmit = { if (draft.isNotBlank()) { onAdd(draft); draft = "" } })
            PrimaryAction("Hide it", ready = draft.isNotBlank(), modifier = Modifier.testTag("hide_word_add")) { if (draft.isNotBlank()) { onAdd(draft); draft = "" } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            words.forEach { w -> Chip("$w  ✕", selected = true, maxLines = 1) { onRemove(w) } }
            SourceRules.SUGGESTED_WORDS.filter { s -> words.none { it.equals(s, ignoreCase = true) } }.forEach { s ->
                Chip("+ $s", maxLines = 1) { onAdd(s) }
            }
        }
    }
}
