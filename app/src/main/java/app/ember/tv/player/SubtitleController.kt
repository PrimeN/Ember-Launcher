package app.ember.tv.player

import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.ember.tv.data.SubtitleCandidate
import app.ember.tv.data.SubtitleConfig
import app.ember.tv.data.SubtitleOption
import app.ember.tv.data.Subtitles
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Finds, ranks, picks and shows subtitles for the playing video:
 * the video's own tracks (via Media3) and downloaded ones (via [SubtitleEngine], which can be
 * shifted in time). Settings > Subtitles decides the providers, languages and auto-selection.
 */
@OptIn(UnstableApi::class)
class SubtitleController(
    private val activity: ComponentActivity,
    private val player: () -> ExoPlayer?,
    val engine: SubtitleEngine,
    /** Can change between episodes (the show's remembered subtitle choice). */
    var config: SubtitleConfig,
    /** Called when the list, status or selection changes, so the panel can redraw. */
    private val onChange: () -> Unit,
    private val nativeSubtitle: (String,String,Boolean) -> Boolean = {_,_,_->false},
) {
    sealed interface Selection {
        data object Off : Selection
        data class Manual(val label:String) : Selection
        data class Embedded(val group: Int, val track: Int) : Selection
        data class External(val candidate: SubtitleCandidate) : Selection
    }

    var candidates: List<SubtitleCandidate> = emptyList(); private set
    var status: String? = null; private set
    var selection: Selection = Selection.Off; private set

    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var searched = false
    private var ready = false
    private val autoSelection = SubtitleAutoSelection()
    private var release: String? = null
    private var query: Subtitles.Query? = null
    private data class Choice(val language:String,val external:Boolean,val provider:String?=null,val forced:Boolean=false)
    private var choice: Choice? = null
    private var chosenOff = false
    private var manualFile:Pair<String,String>?=null
    private var manualBitmap=false
    private data class NativeText(val text: String, val format: String)
    private var nativeText: NativeText? = null
    private var backendLoad: Job? = null
    val wholeFileTiming:Boolean get()=manualFile!=null || manualBitmap

    /** Starts looking for subtitles for a new video. [addon] are the subtitles the Stremio addons gave. */
    fun startSearch(url: String, query: Subtitles.Query, addon: List<SubtitleOption>, preserve: Boolean = false, headers: Map<String, String> = emptyMap()) {
        if (!preserve) { choice=null; chosenOff=false;manualFile=null;manualBitmap=false }
        clearNativeText()
        this.query = query
        searchJob?.cancel()
        loadJob?.cancel()
        engine.clear()
        if(!preserve||!manualBitmap)setMedia3Text(false)
        candidates = emptyList()
        selection = Selection.Off
        searched = false
        ready = player()?.playbackState == androidx.media3.common.Player.STATE_READY
        autoSelection.reset()
        release = query.release
        if(preserve&&(manualFile!=null||manualBitmap)) {
            autoSelection.manual();selection=Selection.Manual("Manual subtitle file");status="Manual subtitle file"
            manualFile?.let {(text,format)->loadJob=activity.lifecycleScope.launch {loadManual(text,format,restoring=true)}}
            onChange();return
        }
        val fromAddons = Subtitles.fromOptions(addon.filter { it.server || config.addonSubs })
        candidates = Subtitles.rank(fromAddons,config,release,player()?.videoFormat?.frameRate?.takeIf { it > 0 })
        searched = candidates.isNotEmpty()
        if (chosenOff) autoSelection.manual()
        val anyProvider = config.stremioOpenSubs || config.openSubsCom || config.subdl
        status = if (anyProvider) "Searching for subtitles…" else null
        onChange()
        autoSelect()
        searchJob = activity.lifecycleScope.launch {
            // The addon's own file hash when it gave one (#74): no need to download parts of the file.
            val hashed: Pair<String, Long?>? = if (!config.hashMatch || !anyProvider) null
                else query.hash?.let { it to query.size } ?: if (url.startsWith("http")) kotlinx.coroutines.withTimeoutOrNull(5000) { Subtitles.movieHash(url, headers) } else null
            val found = if (anyProvider) Subtitles.search(query.copy(hash = hashed?.first, size = hashed?.second), config) { batch ->
                candidates=Subtitles.rank((candidates+batch).distinctBy { it.url ?: it.fileId.toString() },config,release,player()?.videoFormat?.frameRate?.takeIf { it>0 })
                searched=true;onChange();autoSelect()
            } else emptyList()
            ensureActive()
            candidates = Subtitles.rank((candidates + fromAddons + found).distinctBy {it.url ?: it.fileId.toString()}, config, release, player()?.videoFormat?.frameRate?.takeIf { it > 0 })
            searched = true
            status = when {
                candidates.isEmpty() -> "No subtitles found in your languages."
                candidates.any { it.hashMatch } -> "${candidates.size} found · ★ made for this exact file"
                else -> "${candidates.size} found"
            }
            onChange()
            autoSelect()
        }
    }

    /** Late add-on results extend this visit without clearing a manual subtitle choice. */
    fun addOptions(options: List<SubtitleOption>) {
        val found = Subtitles.fromOptions(options.filter { it.server || config.addonSubs })
        candidates = Subtitles.rank((candidates + found).distinctBy { it.url ?: it.fileId.toString() },
            config, release, player()?.videoFormat?.frameRate?.takeIf { it > 0 })
        searched = true
        onChange()
        autoSelect()
    }

    /** Call when the player is ready: the audio language is known then. */
    fun onReady() {
        ready = true
        autoSelect()
    }

    /** Built-in text tracks of the video, as (group index, track index, label). */
    fun embedded(): List<Triple<Int, Int, String>> {
        val p = player() ?: return emptyList()
        val out = ArrayList<Triple<Int, Int, String>>()
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i, true)) continue
                val f = g.getTrackFormat(i)
                val lang = Subtitles.languageName(Subtitles.normalise(f.language))
                val forced = (if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) " (forced)" else "") + (if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) " (SDH)" else "")
                out += Triple(gi, i, listOfNotNull(lang + forced, f.label?.takeIf { it.isNotBlank() && it != f.language }).joinToString(" · "))
            }
        }
        return out
    }

    /** #474: [embedded] as rows: language, Forced and SDH tags, the format, "Built in". */
    fun embeddedRows(): List<SubtitleRow> {
        val p = player() ?: return emptyList()
        val out = ArrayList<SubtitleRow>()
        p.currentTracks.groups.forEach { g ->
            if (g.type != C.TRACK_TYPE_TEXT) return@forEach
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i, true)) continue
                val f = g.getTrackFormat(i)
                val lang = Subtitles.languageName(Subtitles.normalise(f.language))
                val tags = listOfNotNull(
                    if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "FORCED" else null,
                    if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || f.label.orEmpty().contains(Regex("(?i)\\b(sdh|cc|hi)\\b"))) "SDH" else null,
                )
                val name = f.label?.takeIf { it.isNotBlank() && it != f.language && !it.equals(lang, true) }?.let { "$lang · $it" } ?: lang
                out += SubtitleRow(name, tags, SubtitleRow.formatOf(f.codecs ?: f.sampleMimeType, f.sampleMimeType), "Built in")
            }
        }
        return out
    }

    private fun autoSelect() {
        if (!searched || !ready || chosenOff) return
        val p=player() ?: return
        val first=choice?.language ?: Subtitles.wanted(config).firstOrNull() ?: return
        val audio=p.audioFormat?.language?.let(Subtitles::normalise)
        val want=choice!=null || config.autoSelect=="always" || config.autoSelect=="foreign" && audio!=null && audio!="und" && audio.substringBefore('-')!=first.substringBefore('-')
        if(!want)return
        val embedded=if(choice?.external==true)null else embedded().firstOrNull { (gi,ti,_) ->
            val f=p.currentTracks.groups[gi].getTrackFormat(ti)
            Subtitles.normalise(f.language).substringBefore('-')==first.substringBefore('-') &&
                (if(choice?.forced==true)f.selectionFlags and C.SELECTION_FLAG_FORCED!=0 else f.selectionFlags and C.SELECTION_FLAG_FORCED==0)
        }
        if(embedded!=null) { if(autoSelection.claim(true,true))selectEmbedded(embedded.first,embedded.second,manual=false);return }
        val ordered=candidates.filter { it.lang==first }.sortedByDescending { it.provider==choice?.provider && it.forced==choice?.forced }
        if(ordered.isNotEmpty() && autoSelection.claim(true,true))selectExternal(ordered.first(),ordered.drop(1),manual=false)
    }

    fun selectOff() {
        clearNativeText()
        autoSelection.manual()
        loadJob?.cancel()
        engine.clear()
        selection = Selection.Off
        chosenOff=true;choice=null;manualFile=null;manualBitmap=false
        setMedia3Text(enabled = false)
        onChange()
    }

    fun selectEmbedded(group: Int, track: Int, manual: Boolean = true) {
        clearNativeText()
        autoSelection.manual()
        loadJob?.cancel()
        engine.clear()
        val p = player() ?: return
        val g = p.currentTracks.groups.getOrNull(group) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, track))
            .build()
        selection = Selection.Embedded(group, track)
        clearNativeText()
        manualFile=null;manualBitmap=false
        if(manual) { val f=g.getTrackFormat(track);choice=Choice(Subtitles.normalise(f.language),false,forced=f.selectionFlags and C.SELECTION_FLAG_FORCED!=0);chosenOff=false }
        onChange()
    }

    /** Downloads and shows a subtitle; if it cannot be read, tries [fallbacks] in turn. */
    fun selectExternal(c: SubtitleCandidate, fallbacks: List<SubtitleCandidate> = emptyList(), manual: Boolean = true) {
        manualFile=null;manualBitmap=false
        if(manual) { choice=Choice(c.lang,true,c.provider,c.forced);chosenOff=false }
        autoSelection.manual()
        loadJob?.cancel()
        status = "Loading ${c.languageName} subtitles from ${c.provider}…"
        onChange()
        loadJob = activity.lifecycleScope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
            status = "Could not load that subtitle. Try another one."; runCatching { onChange() }
        }) {
            for (s in listOf(c) + fallbacks) {
                val got = Subtitles.download(s,query)
                ensureActive()
                if (got == null) continue
                // #256: a file the player or the drawing engine cannot take moves on to the next one instead of
                // ending playback (and the app) with it.
                val native = try {
                    val fps=player()?.videoFormat?.frameRate?.takeIf {it>0}
                    val text=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {when(got.second) {
                        "microdvd"->app.ember.tv.data.SubtitleFiles.microDvd(got.first,fps)
                        "sami"->app.ember.tv.data.SamiSubtitles.toSrt(got.first,Subtitles.wanted(config))
                        else->got.first
                    }}
                    val format=if(got.second in setOf("microdvd","sami"))"srt" else got.second
                    if(text==null)null
                    else if (nativeSubtitle(text,format,false)) { nativeText = NativeText(text, format); true }
                    else if (engine.load(text, format, fps, config.standardAss && authoredAssEnabled(config.authoredStyles, config.systemStyle))) false
                    else null
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
                if (native != null) {
                    runCatching { if(native)engine.clear() else setMedia3Text(enabled = false) }
                    selection = Selection.External(s)
                    status = "${s.languageName} · ${s.provider}" + if (s.hashMatch) " · ★ made for this file" else ""
                    onChange()
                    return@launch
                }
            }
            status = "Could not load that subtitle. Try another one."
            onChange()
        }
    }

    /** Manual files have the same selection priority as an explicit track choice. */
    suspend fun loadManual(text:String,format:String,restoring:Boolean=false):Boolean {
        clearNativeText()
        if(!restoring)loadJob?.cancel()
        autoSelection.manual();chosenOff=false
        val fps=player()?.videoFormat?.frameRate?.takeIf {it>0}
        val converted=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {when(format) {"microdvd"->app.ember.tv.data.SubtitleFiles.microDvd(text,fps)?:return@withContext null;"sami"->app.ember.tv.data.SamiSubtitles.toSrt(text,Subtitles.wanted(config));else->text}} ?: return false
        val convertedFormat = if (format in setOf("microdvd", "sami")) "srt" else format
        if (nativeSubtitle(converted, convertedFormat, true)) {
            nativeText = NativeText(converted, convertedFormat)
            engine.clear()
            manualBitmap=false;manualFile=text to format;selection=Selection.Manual("Manual subtitle file");status="Manual subtitle file"
            onChange()
            return true
        }
        if(!engine.load(converted, convertedFormat, fps,
                config.standardAss && authoredAssEnabled(config.authoredStyles, config.systemStyle)))return false
        setMedia3Text(false);manualBitmap=false;manualFile=text to format;selection=Selection.Manual("Manual subtitle file");status="Manual subtitle file";onChange();return true
    }
    fun selectManualNative(){clearNativeText();autoSelection.manual();loadJob?.cancel();chosenOff=false;manualFile=null;manualBitmap=true;engine.clear();selection=Selection.Manual("Manual bitmap subtitles");status="Manual bitmap subtitles";onChange()}

    private fun clearNativeText() {
        backendLoad?.cancel()
        backendLoad = null
        nativeText = null
    }

    internal fun returnToStandard() {
        val subtitle = nativeText ?: return
        nativeText = null
        backendLoad?.cancel()
        // The native engine has released its sidecar file. Restore the text overlay for this visit.
        setMedia3Text(false)
        backendLoad = activity.lifecycleScope.launch {
            val fps = player()?.videoFormat?.frameRate?.takeIf { it > 0 }
            if (!engine.load(subtitle.text, subtitle.format, fps,
                    config.standardAss && authoredAssEnabled(config.authoredStyles, config.systemStyle))) {
                selection = Selection.Off
            }
            onChange()
        }
    }

    internal fun close() {
        clearNativeText()
        searchJob?.cancel()
        loadJob?.cancel()
        engine.clear()
    }

    /** Media3's own subtitle tracks on or off; off while a downloaded subtitle is shown. */
    private fun setMedia3Text(enabled: Boolean) {
        val p = player() ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    }

    @Suppress("unused")
    private fun Tracks.textGroups() = groups.filter { it.type == C.TRACK_TYPE_TEXT }
}
