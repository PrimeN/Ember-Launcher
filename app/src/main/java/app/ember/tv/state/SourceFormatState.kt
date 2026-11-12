package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.DetailsUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import app.ember.tv.data.StreamFormatDefaults
import app.ember.tv.data.StreamFormatter
import app.ember.tv.data.StreamOption

/**
 * Stremio: how a source's name and description are shown (Settings > Sources). Split out of
 * HomeViewModel. [details] is the open title, for the runtime and the preview.
 */
class SourceFormatState(
    private val context: android.content.Context,
    private val prefs: Prefs,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val details: () -> DetailsUi?,
) {
    // ---- badges (data/Badges.kt) ----
    var badgeMode by mutableStateOf(prefs.badgeMode); private set
    var detailedBadgeCategoriesOff by mutableStateOf(prefs.detailedBadgeCategoriesOff); private set
    val activeBadgeCategoriesOff: Set<String> get() = if (badgeMode == app.ember.tv.data.Badges.MODERN) detailedBadgeCategoriesOff else emptySet()
    /** #194: single badges switched off in the preview. */
    var badgesOff by mutableStateOf(prefs.badgesOff); private set
    fun toggleBadge(mode: String, badge: app.ember.tv.data.BadgeFilter) {
        badgesOff = app.ember.tv.data.BadgeChoices.toggle(badgesOff, app.ember.tv.data.BadgeChoices.key(mode, badge))
        prefs.badgesOff = badgesOff
    }
    fun showAllBadges(mode: String) { badgesOff = app.ember.tv.data.BadgeChoices.clear(mode, badgesOff); prefs.badgesOff = badgesOff }
    var badgeBusy by mutableStateOf(false); private set
    var badgeNote by mutableStateOf<String?>(null); private set

    /** Loads the badge pack in use (called at start). */
    fun loadBadges(): kotlinx.coroutines.Job = scope.launch { app.ember.tv.data.Badges.load(context, badgeMode) }

    fun updateBadgeMode(mode: String) {
        badgeMode = mode
        prefs.badgeMode = mode
        badgeNote = null
        loadBadges()
    }

    fun toggleDetailedBadgeCategory(id: String) {
        detailedBadgeCategoriesOff = app.ember.tv.data.DetailedBadgeCategories.toggle(detailedBadgeCategoriesOff, id)
        prefs.detailedBadgeCategoriesOff = detailedBadgeCategoriesOff
    }

    /** Custom pack from a link: downloads, checks and switches to it. */
    fun importBadges(url: String) {
        if (badgeBusy) return
        badgeBusy = true
        badgeNote = "Downloading..."
        scope.launch {
            val err = app.ember.tv.data.Badges.importUrl(context, url)
            badgeBusy = false
            badgeNote = err ?: "Loaded."
            if (err == null) updateBadgeMode(app.ember.tv.data.Badges.CUSTOM).also { readCustomBadges(); badgeNote = "Loaded." }
        }
    }

    /** A custom pack sent from the phone page. Returns an error message, or null. */
    suspend fun importBadgesText(text: String): String? {
        val err = app.ember.tv.data.Badges.importText(context, text)
        if (err == null) {
            updateBadgeMode(app.ember.tv.data.Badges.CUSTOM)
            readCustomBadges()
            badgeNote = "Loaded from your phone."
        }
        return err
    }

    // ---- #236/#237: editing the custom pack, from the TV or the phone page
    /** Your custom pack as saved; null until read, or when there is none. */
    var customBadges by mutableStateOf<app.ember.tv.data.BadgePack?>(null); private set
    var customBadgesRead by mutableStateOf(false); private set
    private val badgeEdits = kotlinx.coroutines.sync.Mutex()

    fun readCustomBadges(): kotlinx.coroutines.Job = scope.launch {
        customBadges = app.ember.tv.data.Badges.customPack(context); customBadgesRead = true
    }

    /**
     * One edit of the custom pack: read, change, save, then use it. Edits are queued so fast taps
     * (colour after colour) never lose one. Returns an error message, or null.
     */
    suspend fun editBadges(note: String? = null, change: (app.ember.tv.data.BadgePack) -> app.ember.tv.data.BadgePack): String? = badgeEdits.withLock {
        val current = app.ember.tv.data.Badges.customPack(context) ?: app.ember.tv.data.BadgePack()
        val next = change(current)
        if (next == current) return@withLock null
        app.ember.tv.data.Badges.saveCustom(context, next)?.let { return@withLock it }
        customBadges = next; customBadgesRead = true
        if (badgeMode == app.ember.tv.data.Badges.CUSTOM) loadBadges().join() else updateBadgeMode(app.ember.tv.data.Badges.CUSTOM)
        badgeNote = note
        null
    }

    fun editBadge(id: String, change: (app.ember.tv.data.BadgeFilter) -> app.ember.tv.data.BadgeFilter) {
        scope.launch { editBadges { app.ember.tv.data.BadgeEditor.update(it, id, change) }?.let { badgeNote = it } }
    }

    /** Returns the pattern's problem at once; otherwise adds it. */
    fun addBadge(name: String, pattern: String): String? {
        app.ember.tv.data.BadgeEditor.check(pattern)?.let { return it }
        scope.launch { editBadges("Added ${name.ifBlank { "the badge" }}.") { app.ember.tv.data.BadgeEditor.add(it, name, pattern) }?.let { badgeNote = it } }
        return null
    }

    fun deleteBadge(id: String) { scope.launch { editBadges("Badge deleted.") { app.ember.tv.data.BadgeEditor.delete(it, id) }?.let { badgeNote = it } } }
    fun moveBadge(id: String, step: Int) { scope.launch { editBadges { app.ember.tv.data.BadgeEditor.move(it, id, step) }?.let { badgeNote = it } } }

    /** Replaces the custom pack with the Detailed badges, to edit them. */
    fun copyDetailedBadges() {
        scope.launch {
            badgeEdits.withLock {
                val err = app.ember.tv.data.Badges.copyDetailed(context)
                if (err != null) { badgeNote = err; return@withLock }
                customBadges = app.ember.tv.data.Badges.customPack(context); customBadgesRead = true
                updateBadgeMode(app.ember.tv.data.Badges.CUSTOM)
                badgeNote = "The Detailed badges are now your custom badges. Edit them below."
            }
        }
    }

    /** Badges for a source; empty with legacy, or while the pack loads. */
    fun badgesFor(s: StreamOption): List<app.ember.tv.data.BadgeFilter> {
        @Suppress("UNUSED_VARIABLE") val r = app.ember.tv.data.Badges.rev
        return if (badgeMode == app.ember.tv.data.Badges.LEGACY) emptyList() else app.ember.tv.data.BadgeChoices.mark(badgeMode, badgesOff,
            app.ember.tv.data.DetailedBadgeCategories.filter(badgeMode, detailedBadgeCategoriesOff, app.ember.tv.data.Badges.forSource(s)))
    }

    var streamFormatOn by mutableStateOf(prefs.streamFormatOn); private set
    var streamNameTemplate by mutableStateOf(prefs.streamNameTemplate ?: StreamFormatDefaults.NAME); private set
    var streamDescTemplate by mutableStateOf(prefs.streamDescTemplate ?: StreamFormatDefaults.DESCRIPTION); private set
    val streamNameIsDefault: Boolean get() = streamNameTemplate == StreamFormatDefaults.NAME
    val streamDescIsDefault: Boolean get() = streamDescTemplate == StreamFormatDefaults.DESCRIPTION

    /** Formatted name and description per source; cleared when a template changes. */
    private val formatCache = java.util.IdentityHashMap<StreamOption, Pair<String, String>>()

    fun updateStreamFormatOn(on: Boolean) {
        streamFormatOn = on
        prefs.streamFormatOn = on
    }

    /** Saves a template; a blank one goes back to the default. */
    fun updateStreamTemplate(description: Boolean, text: String) {
        val t = text.trim('\n').takeIf { it.isNotBlank() }
        if (description) {
            streamDescTemplate = t ?: StreamFormatDefaults.DESCRIPTION
            prefs.streamDescTemplate = t?.takeIf { it != StreamFormatDefaults.DESCRIPTION }
        } else {
            streamNameTemplate = t ?: StreamFormatDefaults.NAME
            prefs.streamNameTemplate = t?.takeIf { it != StreamFormatDefaults.NAME }
        }
        formatCache.clear()
    }

    /** The source as the templates show it, or null for the built-in layout (or media server files). */
    fun formatSource(s: StreamOption): Pair<String, String>? {
        // Read every input first, so the screen redraws when any of them changes.
        val on = streamFormatOn
        val nameT = streamNameTemplate
        val descT = streamDescTemplate
        if (!on) return null
        val info = s.info ?: return null
        formatCache[s]?.let { return it }
        val fields = StreamFormatter.fieldsFor(info, details()?.meta?.runtimeMinutes)
        val out = StreamFormatter.render(nameT, fields) to StreamFormatter.render(descT, fields)
        formatCache[s] = out
        return out
    }

    /** Preview for Settings: the first addon source of the open title, or an example. */
    fun previewSource(): Pair<String, String> {
        val real = details()?.streams?.firstOrNull { it.info != null }?.info
        val fields = StreamFormatter.fieldsFor(real ?: StreamFormatDefaults.example(), if (real != null) details()?.meta?.runtimeMinutes else 136)
        return StreamFormatter.render(streamNameTemplate, fields) to StreamFormatter.render(streamDescTemplate, fields)
    }

    val previewIsExample: Boolean get() = details()?.streams?.none { it.info != null } ?: true
}
