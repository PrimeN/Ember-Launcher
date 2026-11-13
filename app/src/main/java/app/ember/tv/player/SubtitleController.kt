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
    val wholeFileTiming:Boolean get()=manualFile!=null || manualBitmap

    /** Starts looking for subtitles for a new video. [addon] are the subtitles the Stremio addons gave. */
    fun startSearch(url: String, query: Subtitles.Query, addon: List<SubtitleOption>, preserve: Boolean = false) {
        if (!preserve) { choice=null; chosenOff=false;manualFile=null;manualBitmap=false }
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
        status = if (anyProvider) "Searching for subtitles..." else null
        onChange()
        autoSelect()
        searchJob = activity.lifecycleScope.launch {
            // The addon's own file hash when it gave one (#74): no need to download parts of the file.
            val hashed: Pair<String, Long?>? = if (!config.hashMatch || !anyProvider) null
                else query.hash?.let { it to query.size } ?: if (url.startsWith("http")) Subtitles.movieHash(url) else null
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
        autoSelection.manual()
        loadJob?.cancel()
        engine.clear()
        selection = Selection.Off
        chosenOff=true;choice=null;manualFile=null;manualBitmap=false
        setMedia3Text(enabled = false)
        onChange()
    }

    fun selectEmbedded(group: Int, track: Int, manual: Boolean = true) {
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
        status = "Loading ${c.languageName} subtitles from ${c.provider}..."
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
                    val text=if(got.second=="microdvd") app.ember.tv.data.SubtitleFiles.microDvd(got.first,player()?.videoFormat?.frameRate?.takeIf {it>0}) else got.first
                    if (text?.let {nativeSubtitle(it,if(got.second=="microdvd")"srt" else got.second,false)}==true) true
                    else if (engine.load(got.first, got.second, player()?.videoFormat?.frameRate?.takeIf { it > 0 })) false
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
        if(!restoring)loadJob?.cancel()
        autoSelection.manual();chosenOff=false
        val fps=player()?.videoFormat?.frameRate?.takeIf {it>0}
        val converted=if(format=="microdvd")app.ember.tv.data.SubtitleFiles.microDvd(text,fps)?:return false else text
        if(nativeSubtitle(converted,if(format=="microdvd")"srt" else format,true)){engine.clear();manualBitmap=false;manualFile=text to format;selection=Selection.Manual("Manual subtitle file");status="Manual subtitle file";onChange();return true}
        if(!engine.load(text,format,fps))return false
        setMedia3Text(false);manualBitmap=false;manualFile=text to format;selection=Selection.Manual("Manual subtitle file");status="Manual subtitle file";onChange();return true
    }
    fun selectManualNative(){autoSelection.manual();loadJob?.cancel();chosenOff=false;manualFile=null;manualBitmap=true;engine.clear();selection=Selection.Manual("Manual bitmap subtitles");status="Manual bitmap subtitles";onChange()}

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
