package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ember.tv.HomeViewModel
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.HeroBanner

/**
 * OptionMenu runs its close action before the picked option, so options that open a level reset
 * [closed] themselves.
 *
 * Picks the catalog that feeds the hero or Light's carousel (#68): any addon catalog that loads on
 * its own, not only those on Home. Catalogs on Home come first; the rest are listed per addon
 * (and per type for addons with many), so no menu holds hundreds of rows. Back goes up a level.
 */
@Composable
internal fun CatalogSourcePicker(vm: HomeViewModel, title: String, selected: String?, onPick: (String?) -> Unit,
    automatic: Boolean = true, eligible: (CatalogDef) -> Boolean = { it.featurable }, close: () -> Unit) {
    var addon by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf<String?>(null) }
    var closed by remember { mutableStateOf(false) }
    if (closed) { SideEffect { close() }; return }
    val all = remember(vm.catalogs.allCatalogs) { vm.catalogs.allCatalogs.filter { vm.addonAvailable(it.addon.url) && eligible(it) } }
    fun pick(key: String?) { onPick(key); closed = true }
    fun option(def: CatalogDef, withAddon: Boolean) = MenuOption(if (withAddon) "${def.label} · ${def.addon.name}" else def.label, selected = selected == def.key) { pick(def.key) }
    key(addon, type) {
        val current = addon
        when {
            current == null -> OptionMenu(title, buildList {
                if (automatic) add(MenuOption("Automatic mix", selected = selected == null) { pick(null) })
                val onHome = vm.rows.distinctBy(HeroBanner::sourceKey)
                onHome.forEach { row ->
                    add(MenuOption("${row.displayTitle} · ${row.addonName}", selected = selected == HeroBanner.sourceKey(row)) { pick(HeroBanner.sourceKey(row)) })
                }
                val homeKeys = onHome.map(HeroBanner::sourceKey).toSet()
                all.filterNot { it.key in homeKeys }.groupBy { it.addon.url }.forEach { (url, defs) ->
                    // Catalogs that are off on Home or in Discover are in here too (#101).
                    add(MenuOption("More from ${defs.first().addon.name} (${defs.size}, including ones that are off)", icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { addon = url; closed = false })
                }
            }) { closed = true }
            else -> {
                val defs = all.filter { it.addon.url == current }
                val types = defs.map { it.type }.distinct()
                val name = defs.firstOrNull()?.addon?.name ?: "Addon"
                val t = type
                if (t == null && defs.size > MAX_ROWS && types.size > 1) OptionMenu(name, types.map { kind ->
                    MenuOption("${kind.replaceFirstChar { it.uppercase() }} (${defs.count { it.type == kind }})", icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { addon = current; type = kind }
                }) { addon = null }
                else OptionMenu(if (t == null) name else "$name · ${t.replaceFirstChar { it.uppercase() }}",
                    defs.filter { t == null || it.type == t }.map { option(it, withAddon = false) }) {
                    if (t != null && defs.size > MAX_ROWS && types.size > 1) { addon = current; type = null } else { addon = null; type = null }
                }
            }
        }
    }
}

/** Above this, an addon's catalogs are split by type first. */
private const val MAX_ROWS = 40

/** What the picker's chip shows for [selected]. */
internal fun catalogSourceLabel(vm: HomeViewModel, selected: String?): String {
    if (selected == null) return "Automatic mix"
    vm.catalogs.allCatalogs.firstOrNull { it.key == selected }?.let { return "${it.label} · ${it.addon.name}" }
    vm.rows.firstOrNull { HeroBanner.sourceKey(it) == selected }?.let { return it.displayTitle }
    return "Unavailable — using automatic mix"
}
