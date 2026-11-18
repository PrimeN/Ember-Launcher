package app.ember.tv.ui

import androidx.core.content.edit

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
    // #305: new labels are written together a moment later, not one full rewrite per label.
    private const val SAVE_DELAY_MS = 3000L
    private val unsaved = linkedSetOf<String>()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val flush = Runnable { save() }
    // #306: answers shared by many settings say nothing on their own.
    private val generic = setOf("off", "on", "auto", "automatic", "always", "never", "normal", "standard", "default",
        "none", "ask", "ask first", "yes", "no", "enabled", "disabled", "use default")
    internal fun meaningful(label: String) = label.lowercase() !in generic && label.count { it.isLetter() } >= 2

    private fun id(e: Entry) = listOf(e.tab.name, e.anchor.orEmpty(), e.section.orEmpty(), e.label).joinToString(SEP.toString())

    private fun parse(line: String): Entry? {
        val p = line.split(SEP)
        if (p.size != 4 || p[3].isBlank()) return null
        val tab = runCatching { SettingsTab.valueOf(p[0]) }.getOrNull() ?: return null
        return Entry(tab, p[1].ifBlank { null }, p[2].ifBlank { null }, p[3])
    }

    /** Reads the seed and saved entries once; true when they are in memory. */
    fun load(context: Context): Boolean {
        if (loaded) return true
        loaded = true
        SettingsOptionSeed.entries.forEach { entries[id(it)] = it }
        val sp = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).also { store = it }
        sp.getStringSet(KEY, emptySet()).orEmpty().mapNotNull(::parse).filter { meaningful(it.label) }.forEach { entries[id(it)] = it }
        return true
    }

    fun record(tab: SettingsTab, anchor: String?, section: String?, label: String) {
        val text = label.trim()
        if (text.isBlank() || tab == SettingsTab.SEARCH || !meaningful(text)) return
        val e = Entry(tab, anchor, section?.trim()?.ifBlank { null }, text)
        val key = id(e)
        if (key in entries || entries.size >= LIMIT) return
        entries[key] = e
        if (store == null) return
        synchronized(unsaved) { unsaved.add(key) }
        main.removeCallbacks(flush)
        main.postDelayed(flush, SAVE_DELAY_MS)
    }

    private fun save() {
        val sp = store ?: return
        val batch = synchronized(unsaved) { unsaved.toSet().also { unsaved.clear() } }
        if (batch.isEmpty()) return
        sp.edit { putStringSet(KEY, sp.getStringSet(KEY, emptySet()).orEmpty() + batch) }
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

    internal fun clearForTest() { main.removeCallbacks(flush); synchronized(unsaved) { unsaved.clear() }; entries.clear(); loaded = false; store = null }
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
