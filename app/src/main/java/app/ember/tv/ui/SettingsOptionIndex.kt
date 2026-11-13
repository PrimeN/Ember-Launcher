package app.ember.tv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalContext

/** The settings page being drawn, so its options can be found from Search (#269). */
internal val LocalSettingsPage = compositionLocalOf<SettingsTab?> { null }

/** The enclosing section's anchor (its id, or its title when it has none). */
internal val LocalSettingsAnchor = compositionLocalOf<String?> { null }

/**
 * #269: every option label on a settings page, remembered as the page is drawn (and seeded from
 * [SettingsOptionSeed]), so Search finds options the hand-written keyword lists miss.
 */
internal object SettingsOptionIndex {
    data class Entry(val tab: SettingsTab, val anchor: String?, val section: String?, val label: String)

    private const val STORE = "ember_settings_index"
    private const val KEY = "entries"
    private const val LIMIT = 4000
    private const val SEP = '\u001F'
    private val entries = mutableStateMapOf<String, Entry>()
    private var loaded = false
    private var store: android.content.SharedPreferences? = null

    private fun id(e: Entry) = listOf(e.tab.name, e.anchor.orEmpty(), e.section.orEmpty(), e.label).joinToString(SEP.toString())

    private fun parse(line: String): Entry? {
        val p = line.split(SEP)
        if (p.size != 4 || p[3].isBlank()) return null
        val tab = runCatching { SettingsTab.valueOf(p[0]) }.getOrNull() ?: return null
        return Entry(tab, p[1].ifBlank { null }, p[2].ifBlank { null }, p[3])
    }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        SettingsOptionSeed.entries.forEach { entries[id(it)] = it }
        val sp = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).also { store = it }
        sp.getStringSet(KEY, emptySet()).orEmpty().mapNotNull(::parse).forEach { entries[id(it)] = it }
    }

    fun record(tab: SettingsTab, anchor: String?, section: String?, label: String) {
        val text = label.trim()
        if (text.isBlank() || tab == SettingsTab.SEARCH) return
        val e = Entry(tab, anchor, section?.trim()?.ifBlank { null }, text)
        val key = id(e)
        if (key in entries || entries.size >= LIMIT) return
        entries[key] = e
        store?.let { sp ->
            val saved = sp.getStringSet(KEY, emptySet()).orEmpty()
            sp.edit().putStringSet(KEY, saved + key).apply()
        }
    }

    /** Entries whose label or section contain every word. */
    fun search(query: String): List<Entry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return entries.values.filter { e ->
            val text = (e.label + " " + e.section.orEmpty()).lowercase()
            words.all { it in text }
        }.distinctBy { Triple(it.tab, it.anchor, it.label.lowercase()) }
            .sortedWith(compareBy({ !it.label.lowercase().startsWith(words.first()) }, { it.tab.ordinal }, { it.label }))
    }

    internal fun clearForTest() { entries.clear(); loaded = false; store = null }
}

/** Records [label] under the page and section it is drawn in; does nothing outside Settings. */
@Composable
internal fun RecordSettingOption(label: String) {
    val tab = LocalSettingsPage.current ?: return
    val anchor = LocalSettingsAnchor.current
    val section = LocalSettingsSection.current
    val context = LocalContext.current
    SideEffect {
        SettingsOptionIndex.load(context)
        SettingsOptionIndex.record(tab, anchor, section, label)
    }
}
