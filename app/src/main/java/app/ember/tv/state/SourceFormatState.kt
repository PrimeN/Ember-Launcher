package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.DetailsUi
import kotlinx.coroutines.launch
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
            if (err == null) updateBadgeMode(app.ember.tv.data.Badges.CUSTOM).also { badgeNote = "Loaded." }
        }
    }

    /** A custom pack sent from the phone page. Returns an error message, or null. */
    suspend fun importBadgesText(text: String): String? {
        val err = app.ember.tv.data.Badges.importText(context, text)
        if (err == null) {
            updateBadgeMode(app.ember.tv.data.Badges.CUSTOM)
            badgeNote = "Loaded from your phone."
        }
        return err
    }

    /** Badges for a source; empty with legacy, or while the pack loads. */
    fun badgesFor(s: StreamOption): List<app.ember.tv.data.BadgeFilter> {
        @Suppress("UNUSED_VARIABLE") val r = app.ember.tv.data.Badges.rev
        return if (badgeMode == app.ember.tv.data.Badges.LEGACY) emptyList() else app.ember.tv.data.DetailedBadgeCategories.filter(
            badgeMode, detailedBadgeCategoriesOff, app.ember.tv.data.Badges.forSource(s))
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
