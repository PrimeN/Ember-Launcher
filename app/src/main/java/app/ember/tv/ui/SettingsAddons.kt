package app.ember.tv.ui

import androidx.core.net.toUri
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.draw.alpha
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
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
/**
 * One added addon as one card (#400): its icon, name, what it has and what it does, and an info
 * button. OK opens one menu with its settings, on/off, rename, refresh, order and remove.
 */
@Composable
internal fun AddonRow(vm: HomeViewModel, url: String) {
    val info = vm.addonInfo[url]
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = vm.addonLabel(url)
    val on = vm.addonAvailable(url)
    var menu by remember(url) { mutableStateOf(false) }
    var renaming by remember(url) { mutableStateOf(false) }
    var removing by remember(url) { mutableStateOf(false) }
    var details by remember(url) { mutableStateOf(false) }
    var configuring by remember(url) { mutableStateOf<String?>(null) }
    val does = info?.let { a -> AddonSummary.does(a.provides, a.epgProvider) } ?: if (on) "Manifest not loaded" else "Off"
    val has = info?.let { a -> AddonSummary.has(a.catalogs.size, a.types) }.orEmpty()
    val warning = info?.configurationRequired == true || info?.adult == true
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Focusable(onClick = { menu = true }, modifier = Modifier.weight(1f).testTag("addon_card"),
            radius = TvTokens.Shape.Card, container = PanelHi, focusedScale = TvTokens.Focus.Inline) {
            val ink = androidx.tv.material3.LocalContentColor.current
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.alpha(if (on) 1f else .45f)) { AddonLogo(info?.logo ?: info?.background, label) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (!on) Text("Off", fontSize = TvTokens.Type.Caption.sp, maxLines = 1,
                            modifier = Modifier.background(ink.copy(alpha = .16f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                        else if (warning) Text(if (info?.configurationRequired == true) "Needs setting up" else "Adult", fontSize = TvTokens.Type.Caption.sp, maxLines = 1,
                            color = Color(0xFFFFB4AB))
                        // #430: how its recent source lookups went.
                        if (on) app.ember.tv.data.AddonHealth.of(url)?.let { h -> AddonHealthChip(h) }
                    }
                    if (has.isNotBlank()) Text(has, fontSize = 13.sp, color = ink.copy(alpha = .78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(does, fontSize = TvTokens.Type.Caption.sp, color = ink.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                androidx.tv.material3.Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = ink.copy(alpha = .7f), modifier = Modifier.size(20.dp))
            }
        }
        ToolbarIcon("About $label", Icons.Outlined.Info) { details = true }
    }
    if (menu) OptionMenu(label, buildList {
        add(MenuOption(if (on) "Turn off" else "Turn on", icon = Icons.Outlined.PowerSettingsNew) { vm.pauseAddon(url, on) })
        info?.configureUrl?.let { link ->
            add(MenuOption(if (info?.configurationRequired == true) "Set it up" else "Its settings", icon = Icons.Outlined.Settings) {
                // #403: Google TV has no browser; its stand-in only says "no app can do this". Then the page opens on a phone.
                val opened = WebLinks.hasBrowser(context) && runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, link.toUri()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!opened) configuring = link
            })
        }
        add(MenuOption("Rename", icon = Icons.Outlined.Edit) { renaming = true })
        add(MenuOption("Refresh", icon = Icons.Outlined.Refresh) { vm.refreshAddon(url) })
        // #267: the order addons are asked in.
        moveOptions(vm.addons.indexOf(url), vm.addons.lastIndex).forEach { (name, step) ->
            add(MenuOption("Move: $name", icon = Icons.Outlined.OpenWith) { vm.moveAddon(url, step) })
        }
        add(MenuOption("Remove", icon = Icons.Outlined.Delete) { removing = true })
    }) { menu = false }
    if (details) SettingInfoDialog(label, AddonSummary.details(info, url, on)) { details = false }
    configuring?.let { link ->
        MediaDialog("${label}: its settings", { configuring = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(link, 220.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Text("This TV has no web browser. Scan the code with your phone to open $label's settings page.", fontSize = 15.sp)
                    Text("The code holds your addon link, which can include your own keys, so only scan it yourself. After saving there, add the new link here or choose Refresh.",
                        color = Mute, fontSize = 13.sp)
                    Chip("Done") { configuring = null }
                }
            }
        }
    }
    if (renaming) AddonRenameDialog(vm, url, info) { renaming = false }
    // #131: removing loses the addon's configured link, so it asks first.
    if (removing) ConfirmDestructive("Remove ${info?.name ?: "this addon"}?",
        "Its catalogs, streams and subtitles stop appearing. To add it back you'll need its link again.",
        { removing = false }, stringResource(R.string.ui_remove)) { vm.removeAddon(url) }
}

/** #403: a web page can open on this TV only with a real browser, not Google TV's placeholder. */
internal object WebLinks {
    private val placeholders = setOf("com.google.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs", "com.google.android.tv.frameworkpackagestub")
    fun isPlaceholder(pkg: String?) = pkg == null || pkg in placeholders
    fun hasBrowser(context: android.content.Context): Boolean = runCatching {
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/"))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        context.packageManager.queryIntentActivities(view, 0).any { !isPlaceholder(it.activityInfo?.packageName) }
    }.getOrDefault(false)
}

/** The words on an addon's card and in its info popup (#400). */
internal object AddonSummary {
    fun does(provides: List<String>, guide: Boolean): String = (provides.mapNotNull {
        when (it) { "catalog" -> "Rows"; "meta" -> "Details"; "stream" -> "Sources"; "subtitles" -> "Subtitles"; else -> null }
    } + listOfNotNull("TV guide".takeIf { guide })).joinToString(" · ").ifBlank { "Nothing this launcher uses" }

    fun has(catalogs: Int, types: List<String>): String = listOfNotNull(
        catalogs.takeIf { it > 0 }?.let { "$it ${if (it == 1) "catalog" else "catalogs"}" },
        types.takeIf { it.isNotEmpty() }?.let { list -> list.take(4).joinToString(", ") + if (list.size > 4) " +${list.size - 4}" else "" },
    ).joinToString(" · ")

    fun details(a: app.ember.tv.data.AddonInfo?, url: String, on: Boolean): String = buildList {
        if (a == null) { add(if (on) "Its manifest has not loaded yet." else "This addon is off."); return@buildList }
        a.description?.takeIf { it.isNotBlank() }?.let(::add)
        add(listOfNotNull(a.version?.let { "Version $it" }, a.manifestId?.let { "ID: $it" }, a.originalName?.let { "Renamed from $it" }).joinToString("\n"))
        add("Provides: " + does(a.provides, a.epgProvider) + if (a.configurable && !a.configurationRequired) " · Has its own settings" else "")
        if (a.types.isNotEmpty()) add("Types: " + a.types.joinToString(", "))
        listOfNotNull(
            "Needs setting up before it works.".takeIf { a.configurationRequired },
            "Adult content · never used on kids profiles.".takeIf { a.adult },
            "Peer-to-peer sources · can share your IP address.".takeIf { a.p2p },
            a.contactEmail?.let { "Contact: $it" },
        ).takeIf { it.isNotEmpty() }?.let { add(it.joinToString("\n")) }
    }.filter { it.isNotBlank() }.joinToString("\n\n")
}

@Composable
private fun AddonRenameDialog(vm: HomeViewModel, url: String, info: app.ember.tv.data.AddonInfo?, close: () -> Unit) {
    var name by remember(url) { mutableStateOf(info?.name.orEmpty()) }
    MediaDialog("Rename ${vm.addonLabel(url)}", close, resizeForKeyboard = true) {
        TextInput(name, { name = it }, info?.originalName ?: info?.name ?: "Name")
        ButtonRow(spacing = 10.dp) {
            Chip(stringResource(R.string.ui_save)) { vm.renameAddon(url, name); close() }
            if (info?.originalName != null) Chip(stringResource(R.string.ui_use_original)) { vm.renameAddon(url, ""); close() }
            Chip(stringResource(R.string.ui_cancel)) { close() }
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
        // #401: one plain line; the longer explanation sits behind the info button.
        Section(stringResource(R.string.ui_catalogs), "Each catalog is a list of titles from an addon. Choose where each one shows.",
            info = stringResource(R.string.ui_choose_which_addon_catalogs_appear_as) + "\n\n" + stringResource(R.string.ui_the_order_is_shared_home_rows) +
                "\n\nHome: a row on the Home screen. Live TV: channels on the Live TV page. Discover: a list to browse on the Discover page.") {
            if (vm.allCatalogs.isEmpty())
                Text(if (vm.addons.isEmpty()) "No addons yet. Add one in ${SettingsTab.ADDONS.path}." else "Loading catalogs…", color = Mute, fontSize = 13.sp)
        }
    }
    // #193: grouped by addon, closed until opened, and by type inside; not one flat list of hundreds.
    val list = vm.allCatalogs
    if (list.isEmpty()) return
    val groups = CatalogBrowse.groups(list, CatalogBrowse.show, CatalogBrowse.type, vm::isOnHome, vm::isOnLive, vm::isInDiscover)
    item(key = "catfilters") { CatalogFilters(vm, list, groups) }
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
/**
 * What the catalog list shows (#193, #401): two dropdown rows instead of rows of buttons (which
 * catalogs, and which type), and one more row for opening every addon and starting over.
 */
@Composable
private fun CatalogFilters(vm: HomeViewModel, list: List<CatalogDef>, groups: List<CatalogBrowse.Group>) {
    var picking by remember { mutableStateOf<String?>(null) }
    val types = CatalogBrowse.types(list)
    val allTypes = stringResource(R.string.st_all_types)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingValueRow("Which catalogs", CatalogBrowse.shows.first { it.first == CatalogBrowse.show }.second.let { if (CatalogBrowse.show == CatalogBrowse.ALL) "All catalogs" else it }) { picking = "show" }
        if (types.size > 1) SettingValueRow("Catalog type", if (CatalogBrowse.type == CatalogBrowse.ALL) allTypes else CatalogBrowse.typeName(CatalogBrowse.type)) { picking = "type" }
        SettingValueRow("Catalog options", "Open, close or reset") { picking = "more" }
        Text(CatalogBrowse.summary(groups.sumOf { it.shown }, list.size, groups.size), color = Mute, fontSize = 13.sp)
    }
    when (picking) {
        "show" -> OptionMenu("Show", CatalogBrowse.shows.map { (id, name) ->
            MenuOption(if (id == CatalogBrowse.ALL) "All catalogs" else name, selected = CatalogBrowse.show == id) { CatalogBrowse.show = id } }) { picking = null }
        "type" -> OptionMenu("Type", listOf(MenuOption(allTypes, selected = CatalogBrowse.type == CatalogBrowse.ALL) { CatalogBrowse.type = CatalogBrowse.ALL }) +
            types.map { type -> MenuOption(CatalogBrowse.typeName(type), selected = CatalogBrowse.type == type) { CatalogBrowse.type = type } }) { picking = null }
        "more" -> OptionMenu("Catalogs", (if (groups.size > 1) listOf(
                MenuOption(stringResource(R.string.st_open_all)) { CatalogBrowse.open = groups.map { it.id }.toSet() },
                MenuOption(stringResource(R.string.st_close_all)) { CatalogBrowse.open = emptySet() }) else emptyList()) +
            MenuOption(stringResource(R.string.ui_reset_order_and_choices), icon = Icons.Outlined.Refresh) { vm.resetCatalogs() }) { picking = null }
    }
}
/** One addon's catalogs (#193): OK opens or closes it; its ⋮ changes all of its listed catalogs at once (#401). */
@Composable
private fun CatalogGroupHeader(vm: HomeViewModel, g: CatalogBrowse.Group, open: Boolean) {
    var bulk by remember(g.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Focusable(onClick = { CatalogBrowse.toggle(g.id) }, modifier = Modifier.weight(1f).testTag("catalog_group"), radius = TvTokens.Shape.Card, container = PanelHi) {
            val ink = androidx.tv.material3.LocalContentColor.current
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "▾" else "▸", fontSize = 18.sp)
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.line(), color = ink.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (g.shown > 1) ToolbarIcon("Change all of ${g.name}", Icons.Outlined.MoreVert) { bulk = true }
    }
    if (bulk) {
        val all = g.byType.flatMap { it.second }.map { it.catalog }
        fun home(on: Boolean) = all.forEach { c -> if (c.homeable && vm.isOnHome(c) != on) vm.toggleCatalogHome(c) }
        fun discover(on: Boolean) = all.forEach { c -> if (c.browsable && vm.isInDiscover(c) != on) vm.toggleCatalogDiscover(c) }
        OptionMenu("${g.name} · ${g.shown} catalogs", listOf(
            MenuOption(stringResource(R.string.st_all_on_home)) { home(true) }, MenuOption(stringResource(R.string.st_none_on_home)) { home(false) },
            MenuOption(stringResource(R.string.st_all_in_discover)) { discover(true) }, MenuOption(stringResource(R.string.st_none_in_discover)) { discover(false) },
        )) { bulk = false }
    }
}
/**
 * One catalog as one card: its place, name and type, and where it shows as small marks. OK opens
 * one menu with Home, Live TV, Discover, Rename and Move, so a long list is one stop per catalog.
 */
@Composable
private fun CatalogCard(vm: HomeViewModel, i: Int, c: CatalogDef, lastIndex: Int) {
    var menu by remember(c.key) { mutableStateOf(false) }
    var renaming by remember(c.key) { mutableStateOf(false) }
    val home = vm.isOnHome(c); val live = vm.isOnLive(c); val discover = vm.isInDiscover(c)
    val homeName = stringResource(R.string.ui_home_b9499); val liveName = stringResource(R.string.ui_live_tv_2386e)
    val discoverName = stringResource(R.string.ui_discover); val renameName = stringResource(R.string.ui_rename)
    Focusable(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp).testTag("catalog_card"),
        radius = TvTokens.Shape.Card, container = PanelHi, focusedScale = TvTokens.Focus.Inline) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(ink.copy(alpha = .10f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("${i + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(CatalogBrowse.cardLine(c.type, c.label, c.originalLabel), color = ink.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CatalogPlace(homeName, home, !c.homeable, ink)
                CatalogPlace(liveName, live, !c.homeable, ink)
                CatalogPlace(discoverName, discover, !c.browsable, ink)
            }
            androidx.tv.material3.Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = ink.copy(alpha = .7f), modifier = Modifier.size(20.dp))
        }
    }
    if (menu) OptionMenu(c.label, buildList {
        // Places a catalog can't go stay in the menu with a lock and say why (#102).
        fun place(label: String, on: Boolean, allowed: Boolean, where: String, toggle: () -> Unit) =
            add(MenuOption(label, selected = on, icon = if (allowed) null else Icons.Outlined.Lock) { if (allowed) toggle() else vm.showNotice(catalogLockReason(c, where)) })
        place(homeName, home, c.homeable, "Home") { vm.toggleCatalogHome(c) }
        place(liveName, live, c.homeable, "Live TV") { vm.toggleCatalogLive(c) }
        place(discoverName, discover, c.browsable, "Discover") { vm.toggleCatalogDiscover(c) }
        add(MenuOption(renameName, icon = Icons.Outlined.Edit) { renaming = true })
        moveOptions(i, lastIndex).forEach { (label, step) -> add(MenuOption(label, icon = Icons.Outlined.OpenWith) { vm.moveCatalog(c, step) }) }
    }) { menu = false }
    if (renaming) CatalogRenameDialog(vm, c.key, c.originalLabel) { renaming = false }
}
/** Where a catalog shows, on its card: ticked when on, faint when off, a lock where it cannot go. */
@Composable
private fun CatalogPlace(label: String, on: Boolean, locked: Boolean, ink: Color) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).then(if (on) Modifier.background(ink.copy(alpha = .16f), shape) else Modifier.border(1.dp, ink.copy(alpha = .22f), shape))
        .padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val alpha = when { on -> 1f; locked -> .4f; else -> .6f }
        androidx.tv.material3.Icon(when { locked -> Icons.Outlined.Lock; on -> Icons.Outlined.Check; else -> Icons.Outlined.Close },
            contentDescription = null, tint = ink.copy(alpha = alpha), modifier = Modifier.size(13.dp))
        Text(label, fontSize = 12.sp, color = ink.copy(alpha = alpha), maxLines = 1)
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
    /** The second line of a catalog's card: its type, and the addon's own name when it was renamed. */
    fun cardLine(type: String, label: String, original: String): String =
        typeName(type) + if (original.isNotBlank() && original != label) " · was $original" else ""

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
        Note(stringResource(R.string.st_collections_are_now_in_settings_home, SettingsTab.WIDGETS.path))
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

/** #430: OK, Slow or Down, from the addon's recent source lookups. */
@Composable
internal fun AddonHealthChip(level: app.ember.tv.data.AddonHealth.Level) {
    val colour = when (level) {
        app.ember.tv.data.AddonHealth.Level.OK -> Color(0xFF7FD48A)
        app.ember.tv.data.AddonHealth.Level.SLOW -> Color(0xFFFFC66B)
        app.ember.tv.data.AddonHealth.Level.DOWN -> Color(0xFFFF8A80)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.background(colour.copy(alpha = .16f), RoundedCornerShape(TvTokens.Shape.Small.dp)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Box(Modifier.size(7.dp).background(colour, androidx.compose.foundation.shape.CircleShape))
        Text(level.label, fontSize = TvTokens.Type.Caption.sp, color = colour, maxLines = 1)
    }
}
