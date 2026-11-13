package app.ember.tv.ui

import app.ember.tv.addAddon
import app.ember.tv.addonLabel
import app.ember.tv.pauseAddon
import app.ember.tv.refreshAddon
import app.ember.tv.moveAddon
import app.ember.tv.removeAddon
import app.ember.tv.renameAddon
import app.ember.tv.updateAddonTimeout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Info
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.HomeViewModel
import app.ember.tv.data.CatalogDef
import androidx.compose.ui.platform.testTag
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Prefs
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.LinkMode
import app.ember.tv.tracker.Tracker
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import app.ember.tv.MainActivity
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Legal
import app.ember.tv.data.About
import app.ember.tv.data.MetaEnhance
import androidx.compose.ui.draw.clip
import app.ember.tv.data.Meta
import app.ember.tv.data.Posters
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Backup
import app.ember.tv.data.Profile
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun LazyListScope.addonsPage(vm: HomeViewModel) {
    item(key = "addonshead") {
        var organizing by remember { mutableStateOf(false) }
        if (organizing) ContentOrganizer(vm) { organizing = false }
        Section(stringResource(R.string.ui_stremio_addons), stringResource(R.string.ui_each_catalog_an_addon_offers_becomes)) {
            Chip(stringResource(R.string.st_organize_add_ons_catalogs_and_collections)) { organizing = true }
            if (vm.addons.size > 1) Text(stringResource(R.string.tx_addon_order_note), color = Mute, fontSize = 13.sp)
            if (vm.addons.isEmpty()) {
                Text(stringResource(R.string.ui_no_addons_yet_paste_a_link), color = Mute, fontSize = 14.sp)
            }
        }
    }
    items(vm.addons, key = { it }) { url -> AddonRow(vm, url) }
    item(key = "addaddon") { AddAddonField(vm) }
    item(key = "addontimeout") {
        Section(stringResource(R.string.ui_time_limit), stringResource(R.string.ui_how_long_home_waits_for_an)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(listOf(5, 10, 15, 30).map { it.toString() to "$it s" }, vm.addonTimeout.toString()) { vm.updateAddonTimeout(it.toInt()) }
                if (vm.slowAddons.isNotEmpty()) {
                    Text(stringResource(R.string.ui_too_slow, vm.slowAddons.joinToString(", ")), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
            }
        }
    }
}
/** One added addon: its name (yours or the manifest's), what it offers, rename and remove. */
@Composable
internal fun AddonRow(vm: HomeViewModel, url: String) {
    val info = vm.addonInfo[url]
    var renaming by remember(url) { mutableStateOf(false) }
    var newName by remember(url, renaming) { mutableStateOf(info?.name.orEmpty()) }
    Column(
        modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // What the addon's manifest says about itself (#60): logo, version, description, id,
        // how many catalogs it has and which types it covers.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AddonLogo(info?.logo ?: info?.background, vm.addonLabel(url)) // its artwork when it has no logo (#71)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.addonLabel(url), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    info?.version?.let { v ->
                        Text("v$v", fontSize = TvTokens.Type.Caption.sp, color = Fg, maxLines = 1,
                            modifier = Modifier.background(WashHi, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                info?.description?.let { Text(it, fontSize = 13.sp, color = Fg, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                info?.originalName?.let { Text(stringResource(R.string.tx_renamed_from, it), fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1) }
                info?.manifestId?.let { Text(stringResource(R.string.tx_id, it), fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (info != null) {
                        if (info.catalogs.isNotEmpty()) {
                            androidx.tv.material3.Icon(Icons.Outlined.Folder, contentDescription = "Catalogs", tint = Mute, modifier = Modifier.size(16.dp))
                            Text(info.catalogs.size.toString(), fontSize = TvTokens.Type.Caption.sp, color = Mute)
                        }
                        if (info.types.isNotEmpty()) {
                            androidx.tv.material3.Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Types", tint = Mute,
                                modifier = Modifier.padding(start = if (info.catalogs.isNotEmpty()) 6.dp else 0.dp).size(16.dp))
                            Text(info.types.joinToString(", "), fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
                Text(
                    info?.let { a ->
                        (a.provides.mapNotNull {
                            when (it) {
                                "catalog" -> "Rows"
                                "meta" -> "Details"
                                "stream" -> "Sources"
                                "subtitles" -> "Subtitles"
                                else -> null
                            }
                        } + listOfNotNull("TV guide".takeIf { a.epgProvider }, "Has its own settings".takeIf { a.configurable && !a.configurationRequired }))
                            .joinToString(" · ").ifBlank { "Nothing this launcher uses" }
                    } ?: if (vm.addonAvailable(url)) "Manifest not loaded" else "Paused",
                    fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // What the manifest says about its content and setup (#71).
        info?.let { a ->
            val flags = listOfNotNull(
                "Needs setting up before it works".takeIf { a.configurationRequired },
                "Adult content · never used on kids profiles".takeIf { a.adult },
                "Peer-to-peer sources · can share your IP address".takeIf { a.p2p },
                a.contactEmail?.let { "Contact: $it" },
            )
            if (flags.isNotEmpty()) Text(flags.joinToString("\n"), fontSize = TvTokens.Type.Caption.sp,
                color = if (a.configurationRequired || a.adult) Color(0xFFFFB4AB) else Mute)
        }
        ToggleRow(stringResource(R.string.st_enabled), null, vm.addonAvailable(url)) { vm.pauseAddon(url, vm.addonAvailable(url)) }
        ButtonRow(spacing = 8.dp) {
            info?.configureUrl?.let { link ->
                val context = androidx.compose.ui.platform.LocalContext.current
                Chip(if (info?.configurationRequired == true) stringResource(R.string.st_set_it_up) else stringResource(R.string.st_its_settings)) {
                    val opened = runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!opened) vm.showNotice("No web browser on this TV. Open the addon's settings from a phone or computer.")
                }
            }
            if (!renaming) Chip(stringResource(R.string.ui_rename)) { renaming = true }
            Chip(stringResource(R.string.st_refresh)) { vm.refreshAddon(url) }
            // #267: the order addons are asked in.
            MoveButton(vm.addonLabel(url), vm.addons.indexOf(url), vm.addons.lastIndex) { step -> vm.moveAddon(url, step) }
            // #131: removing loses the addon's configured link, so it asks first.
            var removing by remember(url) { mutableStateOf(false) }
            Chip(stringResource(R.string.ui_remove)) { removing = true }
            if (removing) ConfirmDestructive("Remove ${info?.name ?: "this addon"}?",
                "Its catalogs, streams and subtitles stop appearing. To add it back you'll need its link again.",
                { removing = false }, stringResource(R.string.ui_remove)) { vm.removeAddon(url) }
        }
        if (renaming) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(newName, { newName = it }, info?.originalName ?: info?.name ?: "Name")
                Chip(stringResource(R.string.ui_save)) {
                    vm.renameAddon(url, newName)
                    renaming = false
                }
                if (info?.originalName != null) {
                    Chip(stringResource(R.string.ui_use_original)) {
                        vm.renameAddon(url, "")
                        renaming = false
                    }
                }
                Chip(stringResource(R.string.ui_cancel)) { renaming = false }
            }
        }
    }
}
@Composable
internal fun AddAddonField(vm: HomeViewModel) {
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Section(stringResource(R.string.ui_add_an_addon)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(link, { link = it; message = null }, "https://example.com/manifest.json", width = 760)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.ui_add_addon)) {
                    message = vm.addAddon(link)
                    if (message == null) link = ""
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Catalogs
internal fun LazyListScope.catalogsPage(vm: HomeViewModel) {
    item(key = "catshead") {
        Section(stringResource(R.string.ui_catalogs), stringResource(R.string.ui_choose_which_addon_catalogs_appear_as)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ui_the_order_is_shared_home_rows),
                    color = Mute,
                    fontSize = 13.sp,
                    modifier = Modifier.width(760.dp),
                )
                if (vm.allCatalogs.isEmpty()) {
                    Text(if (vm.addons.isEmpty()) "No addons yet. Add one in Settings > Addons." else "Loading catalogs...", color = Mute, fontSize = 13.sp)
                } else {
                    Chip(stringResource(R.string.ui_reset_order_and_choices)) { vm.resetCatalogs() }
                }
            }
        }
    }
    // #193: grouped by addon, closed until opened, and by type inside; not one flat list of hundreds.
    val list = vm.allCatalogs
    if (list.isEmpty()) return
    val groups = CatalogBrowse.groups(list, CatalogBrowse.show, CatalogBrowse.type, vm::isOnHome, vm::isOnLive, vm::isInDiscover)
    item(key = "catfilters") { CatalogFilters(list, groups) }
    if (groups.isEmpty()) item(key = "catnone") { Text(stringResource(R.string.tx_no_catalogs_match_these_filters), color = Mute, fontSize = 14.sp) }
    groups.forEach { g ->
        val open = CatalogBrowse.isOpen(g.id, list.size)
        item(key = "catgroup:${g.id}") { CatalogGroupHeader(vm, g, open) }
        if (open) g.byType.forEach { (type, entries) ->
            if (g.byType.size > 1) item(key = "cattype:${g.id}:$type") {
                Text("${CatalogBrowse.typeName(type)} · ${entries.size}", color = Mute, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            }
            items(entries, key = { "cat:${it.catalog.key}" }) { e -> CatalogCard(vm, e.index, e.catalog, list.lastIndex) }
        }
    }
}
/** What the catalog list shows (#193): the Show and Type filters, and opening or closing every addon. */
@Composable
private fun CatalogFilters(list: List<CatalogDef>, groups: List<CatalogBrowse.Group>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ButtonRow(spacing = 8.dp) { CatalogBrowse.shows.forEach { (id, name) -> Chip(name, selected = CatalogBrowse.show == id) { CatalogBrowse.show = id } } }
        val types = CatalogBrowse.types(list)
        if (types.size > 1) ButtonRow(spacing = 8.dp) {
            Chip(stringResource(R.string.st_all_types), selected = CatalogBrowse.type == CatalogBrowse.ALL) { CatalogBrowse.type = CatalogBrowse.ALL }
            types.forEach { type -> Chip(CatalogBrowse.typeName(type), selected = CatalogBrowse.type == type) { CatalogBrowse.type = type } }
        }
        if (list.size > CatalogBrowse.SMALL && groups.size > 1) ButtonRow(spacing = 8.dp) {
            Chip(stringResource(R.string.st_open_all)) { CatalogBrowse.open = groups.map { it.id }.toSet() }
            Chip(stringResource(R.string.st_close_all)) { CatalogBrowse.open = emptySet() }
        }
        Text(CatalogBrowse.summary(groups.sumOf { it.shown }, list.size, groups.size), color = Mute, fontSize = 13.sp)
    }
}
/** One addon's catalogs (#193): OK opens or closes it. Open, it can switch all of its listed catalogs at once. */
@Composable
private fun CatalogGroupHeader(vm: HomeViewModel, g: CatalogBrowse.Group, open: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Focusable(onClick = { CatalogBrowse.toggle(g.id) }, modifier = Modifier.fillMaxWidth().testTag("catalog_group"), radius = TvTokens.Shape.Card, container = PanelHi) {
            val ink = androidx.tv.material3.LocalContentColor.current
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "▾" else "▸", fontSize = 18.sp)
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.line(), color = ink.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (open && g.shown > 1) ButtonRow(spacing = 8.dp) {
            val all = g.byType.flatMap { it.second }.map { it.catalog }
            fun home(on: Boolean) = all.forEach { c -> if (c.homeable && vm.isOnHome(c) != on) vm.toggleCatalogHome(c) }
            fun discover(on: Boolean) = all.forEach { c -> if (c.browsable && vm.isInDiscover(c) != on) vm.toggleCatalogDiscover(c) }
            Chip(stringResource(R.string.st_all_on_home)) { home(true) }; Chip(stringResource(R.string.st_none_on_home)) { home(false) }
            Chip(stringResource(R.string.st_all_in_discover)) { discover(true) }; Chip(stringResource(R.string.st_none_in_discover)) { discover(false) }
        }
    }
}
/** One catalog on one line (#204): its place and name, then where it shows. Its addon is the group it sits in. */
@Composable
private fun CatalogCard(vm: HomeViewModel, i: Int, c: CatalogDef, lastIndex: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${i + 1}", color = Mute, fontSize = 13.sp, modifier = Modifier.width(26.dp))
        Text(c.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Places a catalog can't go are locked switches that say why (#102), not text.
        SwitchChip(stringResource(R.string.ui_home_b9499), vm.isOnHome(c), locked = !c.homeable) {
            if (c.homeable) vm.toggleCatalogHome(c) else vm.showNotice(catalogLockReason(c, "Home"))
        }
        SwitchChip(stringResource(R.string.ui_live_tv_2386e), vm.isOnLive(c), locked = !c.homeable) {
            if (c.homeable) vm.toggleCatalogLive(c) else vm.showNotice(catalogLockReason(c, "Live TV"))
        }
        SwitchChip(stringResource(R.string.ui_discover), vm.isInDiscover(c), locked = !c.browsable) {
            if (c.browsable) vm.toggleCatalogDiscover(c) else vm.showNotice(catalogLockReason(c, "Discover"))
        }
        CatalogRenameButton(vm, c.key, c.originalLabel)
        MoveButton(c.label, i, lastIndex) { step -> vm.moveCatalog(c, step) }
    }
}
/**
 * How Settings > Addons & catalogs lists catalogs (#193): under their addon, by type, filtered.
 * The state lives here because the list is built outside composition; it lasts while Ember runs.
 */
internal object CatalogBrowse {
    const val ALL = "all"; const val HOME = "home"; const val LIVE = "live"; const val DISCOVER = "discover"; const val OFF = "off"
    val shows = listOf(ALL to "All", HOME to "On Home", LIVE to "On Live TV", DISCOVER to "In Discover", OFF to "Not shown")
    /** Up to this many catalogs, every addon is simply open. */
    const val SMALL = 12
    var open by mutableStateOf<Set<String>>(emptySet())
    var show by mutableStateOf(ALL)
    var type by mutableStateOf(ALL)

    /** [index] is the catalog's place in the shared order, which Move changes. */
    data class Entry(val index: Int, val catalog: CatalogDef)
    data class Group(val id: String, val name: String, val total: Int, val onHome: Int, val inDiscover: Int, val byType: List<Pair<String, List<Entry>>>) {
        val shown: Int get() = byType.sumOf { it.second.size }
        fun line(): String = listOfNotNull(if (shown == total) count(total) else "$shown of ${count(total)}",
            "$onHome on Home".takeIf { onHome > 0 }, "$inDiscover in Discover".takeIf { inDiscover > 0 }).joinToString(" · ")
    }
    private fun count(n: Int) = if (n == 1) "1 catalog" else "$n catalogs"

    fun typeName(type: String): String = when (type.lowercase()) {
        "movie" -> "Movies"; "series" -> "Series"; "tv", "channel" -> "Live TV"; "anime" -> "Anime"
        else -> type.replaceFirstChar { it.uppercase() }.ifBlank { "Other" }
    }
    fun types(list: List<CatalogDef>): List<String> = list.map { it.type }.distinct()

    /** Addons in the order their first catalog holds; inside, types in the order they first appear. */
    fun groups(list: List<CatalogDef>, show: String, type: String, onHome: (CatalogDef) -> Boolean, onLive: (CatalogDef) -> Boolean, inDiscover: (CatalogDef) -> Boolean): List<Group> =
        list.withIndex().groupBy { it.value.addon.url }.mapNotNull { (id, all) ->
            val kept = all.filter { (_, c) ->
                (type == ALL || c.type == type) && when (show) {
                    HOME -> onHome(c); LIVE -> onLive(c); DISCOVER -> inDiscover(c)
                    OFF -> !onHome(c) && !onLive(c) && !inDiscover(c)
                    else -> true
                }
            }
            if (kept.isEmpty()) null else Group(id, all.first().value.addon.name, all.size, all.count { onHome(it.value) }, all.count { inDiscover(it.value) },
                kept.groupBy { it.value.type }.map { (t, rows) -> t to rows.map { Entry(it.index, it.value) } })
        }

    fun isOpen(id: String, total: Int): Boolean = total <= SMALL || id in open
    fun toggle(id: String) { open = if (id in open) open - id else open + id }
    fun summary(shown: Int, total: Int, addons: Int): String =
        (if (shown == total) count(total) else "$shown of ${count(total)}") + " from " + if (addons == 1) "1 addon" else "$addons addons"
}

// ---------------------------------------------------------------- Live TV (IPTV)
@Composable
internal fun StremioFormatPage(vm: HomeViewModel) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // null: none, false: name, true: description
    var showReference by remember { mutableStateOf(false) }
    val (previewName, previewDesc) = vm.sourceFormat.previewSource()
    val on = vm.sourceFormat.streamFormatOn

    // Three parts, in this order: how sources are tagged (badges), how their text is written
    // (format and templates, shown only when the custom format is on), and Collections.
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PartHeader("Badges")
        BadgesSection(vm)

        PartHeader("Source text")
        Section(stringResource(R.string.ui_stream_results), stringResource(R.string.ui_how_addon_sources_are_shown_on)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow(stringResource(R.string.ui_custom_format), null, on) { vm.sourceFormat.updateStreamFormatOn(!on) }
                Note(
                    if (on) stringResource(R.string.ui_templates_use_the_same_syntax_as) +
                        stringResource(R.string.st_a_connected_keyboard_can_make_editing)
                    else stringResource(R.string.st_off_sources_show_the_title_ember),
                )
            }
        }
        if (on) {
            Section(stringResource(R.string.ui_preview), if (vm.sourceFormat.previewIsExample) stringResource(R.string.st_an_example_source_open_a_title) else stringResource(R.string.st_the_first_source_of_the_title)) {
                Row(
                    modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(previewName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.width(260.dp))
                    Text(previewDesc, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
            TemplateSection(
                title = "Name",
                subtitle = "Left column of each source.",
                value = vm.sourceFormat.streamNameTemplate,
                isDefault = vm.sourceFormat.streamNameIsDefault,
                editing = editing == false,
                onEdit = { editing = false },
                onSave = { vm.sourceFormat.updateStreamTemplate(false, it); editing = null },
                onCancel = { editing = null },
                onReset = { vm.sourceFormat.updateStreamTemplate(false, "") },
            )
            TemplateSection(
                title = "Description",
                subtitle = "Right column of each source.",
                value = vm.sourceFormat.streamDescTemplate,
                isDefault = vm.sourceFormat.streamDescIsDefault,
                editing = editing == true,
                onEdit = { editing = true },
                onSave = { vm.sourceFormat.updateStreamTemplate(true, it); editing = null },
                onCancel = { editing = null },
                onReset = { vm.sourceFormat.updateStreamTemplate(true, "") },
            )
            Chip(if (showReference) stringResource(R.string.st_hide_the_template_reference) else stringResource(R.string.ui_template_reference)) { showReference = !showReference }
            if (showReference) {
                Section(stringResource(R.string.ui_template_reference)) {
                    Text(
                        """
                        Values: {stream.resolution}  {stream.quality}  {stream.encode}  {stream.visualTags}  {stream.audioTags}  {stream.audioChannels}  {stream.languages}  {stream.size}  {stream.bitrate}  {stream.title}  {stream.year}  {stream.seasonEpisode}  {stream.edition}  {stream.network}  {stream.releaseGroup}  {stream.seeders}  {stream.indexer}  {stream.filename}  {stream.message}  {stream.upscaled}  {stream.remastered}  {stream.repack}  {stream.unrated}  {stream.uncensored}  {service.cached}  {service.name}  {service.shortName}  {addon.name}
                        Checks: {field::exists["shown when it has a value"||"otherwise"]}  ::istrue  ::isfalse  ::>0  ::<5  ::=text  ::~contains  ::${'$'}startswith  ::^endswith, joined with ::and:: ::or::
                        Changes: ::replace('a','b')  ::join(' | ')  ::upper  ::lower  ::title  ::smallcaps  ::truncate(20)  ::first  ::length  ::bytes  ::sbytes  ::bitrate  ::sbitrate  ::default('n/a')
                        Lines that come out empty are left out. {tools.newLine} starts a new line inside a check.
                        service.cached is true for cached debrid links, false for ones that still have to download, and empty for plain links and torrents. It comes from the addon's own data when it sends it (AIOStreams with stream data on), otherwise from its labels such as ⚡, [RD+], ⏳ or [RD download].
                        """.trimIndent(),
                        color = Mute,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.width(760.dp),
                    )
                }
            }
        }

        // #163: Collections moved to Settings > Home > Widgets & collections.
        Note(stringResource(R.string.st_collections_are_now_in_settings_home))
    }
}
/** A heading between the parts of a long Settings page. */
@Composable
private fun PartHeader(text: String) {
    Column(Modifier.width(760.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Mute.copy(alpha = 0.35f)))
    }
}
@Composable
internal fun TemplateSection(
    title: String,
    subtitle: String,
    value: String,
    isDefault: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(editing, value) { mutableStateOf(value) }
    Section(title, subtitle + if (isDefault) stringResource(R.string.st_default) else stringResource(R.string.st_custom)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Buttons sit above the text, so they stay easy to reach while the text box has focus.
            ButtonRow(spacing = 10.dp) {
                if (editing) {
                    Chip(stringResource(R.string.ui_save)) { onSave(draft) }
                    Chip(stringResource(R.string.ui_cancel)) { onCancel() }
                } else {
                    Chip(stringResource(R.string.ui_edit)) { onEdit() }
                    if (!isDefault) Chip(stringResource(R.string.ui_reset_to_default)) { onReset() }
                }
            }
            if (editing) {
                TvTextField(
                    draft,
                    { draft = it },
                    Modifier.width(760.dp),
                    singleLine = false,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    minHeight = 260.dp,
                )
            } else {
                Box(Modifier.width(760.dp).background(Panel, RoundedCornerShape(10.dp)).padding(14.dp)) {
                    Text(value, fontSize = 12.sp, lineHeight = 17.sp, color = Mute, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Where to watch
/** The addon's logo from its manifest, or its initial when it has none or it does not load. */
@Composable
private fun AddonLogo(logo: String?, name: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var failed by remember(logo) { mutableStateOf(logo == null) }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(WashHi), contentAlignment = Alignment.Center) {
        if (failed) Text(name.trim().take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        else coil.compose.AsyncImage(
            model = remember(logo) {
                coil.request.ImageRequest.Builder(context).data(logo).size(168)
                    .decoderFactory(coil.decode.SvgDecoder.Factory()).build()
            },
            contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            onError = { failed = true }, modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}
/** Why a catalog can't be switched on for [place] (#102). */
internal fun catalogLockReason(c: app.ember.tv.data.CatalogDef, place: String): String = when {
    c.searchOnly -> "${c.label} only answers searches, so it can't be on $place. Search uses it."
    place == "Discover" -> "${c.label} needs a choice Ember can't make for it, so it can't be in Discover."
    else -> "${c.label} needs a genre or another choice before it loads, so it can't be a $place row. Use it in Discover, a collection or the hero."
}
