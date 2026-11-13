package app.ember.tv.remote

import android.app.Application
import android.media.AudioManager
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta
import app.ember.tv.isSaved
import app.ember.tv.markTitleWatched
import app.ember.tv.markWatched
import app.ember.tv.player.PlayerRemote
import app.ember.tv.toggleWatchlist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * #241: the phone remote's requests, answered from what the TV already shows. Search uses the TV's
 * search, a title opens on the TV, its sources are the TV's list, and a source plays as if picked on
 * the TV. Titles go to the phone as short references; addon links, keys and file paths never do.
 */
internal class RemoteController(private val vm: HomeViewModel) {
    private val refs = object : LinkedHashMap<String, Meta>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Meta>?) = size > 500
    }

    private fun ref(m: Meta): String {
        val key = RemoteFormat.refOf(m)
        synchronized(refs) { refs[key] = m }
        return key
    }

    private fun meta(ref: String?): Meta? = ref?.let { synchronized(refs) { refs[it] } }

    private class Box<T>(val value: T)
    /** Runs on the TV's main thread; a null answer stays an answer, only a timeout is an error. */
    private fun <T> main(block: () -> T): T = (RemoteBridge.onMain(5000) { Box(block()) } ?: throw IllegalStateException("The TV is busy. Try again.")).value

    private fun item(m: Meta): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref(m)))
        put("title", JsonPrimitive(m.name.ifBlank { "Untitled" }))
        put("sub", JsonPrimitive(RemoteFormat.subtitle(m)))
        put("image", (m.poster ?: m.background)?.takeIf(RemoteFormat::web)?.let(::JsonPrimitive) ?: JsonNull)
        put("type", JsonPrimitive(m.type))
        put("tref", JsonPrimitive(RemoteFormat.titleRef(m)))
        put("saved", JsonPrimitive(vm.isSaved(m)))
        m.progress?.takeIf { it in 0f..1f }?.let { put("progress", JsonPrimitive(it)) }
    }

    fun call(name: String, p: Map<String, String>): String? = when (name) {
        "state" -> state().toString()
        "search" -> search(p["q"].orEmpty()).toString()
        "lists" -> main { buildJsonObject {
            put("progress", JsonArray(vm.inProgressItems.take(60).map(::item)))
            put("watchlist", JsonArray(vm.combinedWatchlist.take(80).map(::item)))
        } }.toString()
        "details" -> details().toString()
        // #257: the TV's crash log, newest first, so a crash can be passed on from the phone.
        "crashes" -> buildJsonObject {
            put("entries", JsonArray(app.ember.tv.data.CrashLog.entries().take(6).map { JsonPrimitive(it.take(12_000)) }))
        }.toString()
        "open" -> { val m = meta(p["ref"]) ?: return gone(); main { vm.openDetails(m) }; ok() }
        "season" -> { val n = p["season"]?.toIntOrNull() ?: return bad(); main { vm.selectSeason(n) }; ok() }
        "episode" -> {
            val season = p["season"]?.toIntOrNull(); val episode = p["episode"]?.toIntOrNull()
            main { vm.details?.episodes?.firstOrNull { it.season == season && it.episode == episode }?.let { vm.selectEpisode(it); true } ?: false }
                .let { if (it) ok() else RemoteServer.error("That episode is not listed now.") }
        }
        "source" -> {
            val index = p["index"]?.toIntOrNull() ?: return bad()
            main {
                val d = vm.details ?: return@main "Open a title first."
                val s = RemoteFormat.sources(d.serverStreams, d.streams).getOrNull(index) ?: return@main "That source is gone. Reload the list."
                val request = vm.playRequest(s) ?: return@main "That source cannot play."
                val screen = RemoteBridge.screen ?: return@main "Open Ember on the TV first."
                screen.play(request); null
            }?.let(RemoteServer::error) ?: ok()
        }
        "player" -> if (PlayerRemote.command(p["action"].orEmpty(), p["value"]?.toLongOrNull())) ok() else RemoteServer.error("Nothing is playing in Ember.")
        "track" -> if (PlayerRemote.track(p["kind"].orEmpty(), p["index"]?.toIntOrNull() ?: -1)) ok() else RemoteServer.error("That choice is no longer offered.")
        "volume" -> volume(p["action"].orEmpty(), p["level"]?.toIntOrNull()).toString()
        "watched" -> {
            val m = meta(p["ref"])
            main { if (m != null) vm.markTitleWatched(m) else if (vm.details != null) vm.markWatched() else return@main false; true }
                .let { if (it) ok() else gone() }
        }
        "watchlist" -> { val m = meta(p["ref"]) ?: main { vm.details?.meta } ?: return gone(); main { vm.toggleWatchlist(m) }; ok() }
        "text" -> {
            val text = p["text"].orEmpty().take(500)
            val submit = p["submit"] == "1"
            val typed = RemoteBridge.type(text, replace = p["replace"] == "1", submit = submit)
            // With no box being edited, typed words become a search on the TV.
            val searched = !typed && text.isNotBlank() && (main { RemoteBridge.screen?.search(text) } != null)
            buildJsonObject { put("ok", JsonPrimitive(typed || searched)); put("typed", JsonPrimitive(typed)); put("searched", JsonPrimitive(searched)) }.toString()
        }
        "backspace" -> if (RemoteBridge.backspace()) ok() else RemoteServer.error("No text box is being edited on the TV.")
        "key" -> key(p["key"].orEmpty())
        "prompt" -> if (answer(p["kind"].orEmpty(), p["choice"].orEmpty())) ok() else RemoteServer.error("The TV is no longer asking that.")
        else -> null
    }

    private fun ok() = """{"ok":true}"""
    private fun bad() = RemoteServer.error("Something is missing. Reload the page.")
    private fun gone() = RemoteServer.error("That title is no longer listed. Reload the page.")

    private fun state(): JsonObject {
        val now = PlayerRemote.now()
        val nowMeta = now?.meta
        return main {
            buildJsonObject {
                put("now", if (now == null) JsonNull else buildJsonObject {
                    put("title", JsonPrimitive(now.title)); put("sub", JsonPrimitive(now.subtitle))
                    put("image", now.image?.takeIf(RemoteFormat::web)?.let(::JsonPrimitive) ?: JsonNull)
                    put("position", JsonPrimitive(now.positionMs)); put("duration", JsonPrimitive(now.durationMs))
                    put("playing", JsonPrimitive(now.playing)); put("live", JsonPrimitive(now.live))
                    // #249: sound and subtitles, to change from the phone.
                    put("audio", RemoteFormat.choices(now.audio)); put("subtitles", RemoteFormat.choices(now.subtitles))
                    if (nowMeta != null) { put("ref", JsonPrimitive(ref(nowMeta))); put("tref", JsonPrimitive(RemoteFormat.titleRef(nowMeta))); put("saved", JsonPrimitive(vm.isSaved(nowMeta))); put("watched", JsonPrimitive(vm.isWatched(nowMeta))) }
                })
                put("details", vm.details?.meta?.let { m -> JsonPrimitive(ref(m)) } ?: JsonNull)
                put("prompt", prompt()?.let(RemoteFormat::prompt) ?: JsonNull)
                put("typing", JsonPrimitive(RemoteBridge.focusedText != null))
                put("volume", volumeState())
            }
        }
    }

    /**
     * #243: the question the TV is asking now, if any, with the TV's own wording: the player's Up next
     * and Skip, then Resume or start over, then Play with.
     */
    private fun prompt(): app.ember.tv.player.RemotePrompt? {
        PlayerRemote.prompt()?.let { return it }
        val ctx = vm.getApplication<Application>()
        vm.resumeAsk?.let { (req, label) ->
            return app.ember.tv.player.RemotePrompt("resume", ctx.getString(app.ember.tv.R.string.ui_continue_watching), req.title,
                listOf("resume" to "▶ $label", "start" to ctx.getString(app.ember.tv.R.string.ui_start_over), "cancel" to ctx.getString(app.ember.tv.R.string.ui_cancel)))
        }
        vm.playing.chooser?.let { req ->
            return app.ember.tv.player.RemotePrompt("player", ctx.getString(app.ember.tv.R.string.ui_play_with), req.title,
                listOf(app.ember.tv.data.Prefs.PLAYER_BUILTIN to ctx.getString(app.ember.tv.R.string.ui_built_in_player)) + vm.playing.players.map { it.pkg to it.label } +
                    ("cancel" to ctx.getString(app.ember.tv.R.string.ui_cancel)))
        }
        return null
    }

    /** Picks one of [prompt]'s choices on the TV, as if chosen there. */
    private fun answer(kind: String, choice: String): Boolean {
        if (kind == "upnext" || kind == "skip") return PlayerRemote.answer(kind, choice)
        return main {
            val screen = RemoteBridge.screen
            when (kind) {
                "resume" -> {
                    val (req, _) = vm.resumeAsk ?: return@main false
                    if (choice !in setOf("resume", "start", "cancel")) return@main false
                    vm.resumeAsk = null
                    if (choice != "cancel") screen?.playWith(req.copy(resumeDecided = true, startOver = choice == "start"), app.ember.tv.data.Prefs.PLAYER_BUILTIN) ?: return@main false
                    true
                }
                "player" -> {
                    val req = vm.playing.chooser ?: return@main false
                    val allowed = setOf(app.ember.tv.data.Prefs.PLAYER_BUILTIN, "cancel") + vm.playing.players.map { it.pkg }
                    if (choice !in allowed) return@main false
                    vm.playing.dismissChooser()
                    if (choice != "cancel") screen?.playWith(req, choice) ?: return@main false
                    true
                }
                else -> false
            }
        }
    }

    private fun search(query: String): JsonElement {
        val q = query.trim().take(100)
        if (q.length < 2) return buildJsonObject { put("items", JsonArray(emptyList())) }
        main { vm.search.setQuery(q, immediate = true) }
        // Wait for the TV's search, a little at a time, as its catalogs answer.
        val until = System.currentTimeMillis() + 9000
        Thread.sleep(400)
        while (System.currentTimeMillis() < until && main { vm.search.searching }) Thread.sleep(250)
        return main {
            val found = (vm.search.searchSaved + vm.search.searchRows.flatMap { it.items }).distinctBy { it.type + ":" + it.id }.take(48)
            buildJsonObject {
                put("items", JsonArray(found.map(::item)))
                vm.search.searchNote?.let { put("note", JsonPrimitive(it)) }
                put("searching", JsonPrimitive(vm.search.searching))
            }
        }
    }

    private fun details(): JsonElement = main {
        val d = vm.details ?: return@main buildJsonObject { put("open", JsonPrimitive(false)) }
        val m = d.meta
        buildJsonObject {
            put("open", JsonPrimitive(true))
            put("item", item(m))
            put("background", (m.background ?: m.poster)?.takeIf(RemoteFormat::web)?.let(::JsonPrimitive) ?: JsonNull)
            put("description", JsonPrimitive(m.description.orEmpty().take(600)))
            put("watched", JsonPrimitive(vm.detailsWatched)); put("saved", JsonPrimitive(vm.isSaved(m)))
            put("seasons", buildJsonArray { d.seasons.forEach { add(JsonPrimitive(it)) } })
            d.season?.let { put("season", JsonPrimitive(it)) }
            put("episodes", buildJsonArray { d.episodes.filter { d.season == null || it.season == d.season }.take(200).forEach { e -> add(buildJsonObject {
                put("season", JsonPrimitive(e.season)); put("episode", JsonPrimitive(e.episode)); put("title", JsonPrimitive(e.title))
                put("image", e.thumbnail?.takeIf(RemoteFormat::web)?.let(::JsonPrimitive) ?: JsonNull)
                put("chosen", JsonPrimitive(d.episode?.let { it.season == e.season && it.episode == e.episode } == true))
            }) } })
            put("loading", JsonPrimitive(d.loadingStreams || d.loadingServer))
            put("needsEpisode", JsonPrimitive(m.type != "movie" && d.episode == null && d.episodes.isNotEmpty()))
            d.note?.let { put("note", JsonPrimitive(it)) }
            put("sources", buildJsonArray {
                RemoteFormat.sources(d.serverStreams, d.streams).take(120).forEachIndexed { i, s ->
                    val shown = vm.sourceFormat.formatSource(s)
                    add(buildJsonObject {
                        put("index", JsonPrimitive(i))
                        put("title", JsonPrimitive(shown?.first ?: s.name.ifBlank { s.addonName }))
                        put("desc", JsonPrimitive((shown?.second ?: s.title).take(300)))
                        put("badges", buildJsonArray { vm.sourceFormat.badgesFor(s).take(8).forEach { b -> add(JsonPrimitive(b.name)) } })
                    })
                }
            })
        }
    }

    private fun audio() = vm.getApplication<Application>().getSystemService(AudioManager::class.java)

    private fun volumeState(): JsonObject {
        val a = audio()
        return buildJsonObject {
            put("level", JsonPrimitive(a.getStreamVolume(AudioManager.STREAM_MUSIC))); put("max", JsonPrimitive(a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
            put("fixed", JsonPrimitive(a.isVolumeFixed))
        }
    }

    /** The TV's media volume. On TVs that control a soundbar or receiver over HDMI, up and down still work when a level cannot be set. */
    private fun volume(action: String, level: Int?): JsonObject {
        val a = audio()
        val flags = AudioManager.FLAG_SHOW_UI
        when (action) {
            "up" -> a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
            "down" -> a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
            "mute" -> a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, flags)
            "set" -> level?.let { a.setStreamVolume(AudioManager.STREAM_MUSIC, it.coerceIn(0, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), flags) }
        }
        return buildJsonObject { put("ok", JsonPrimitive(true)); put("volume", volumeState()) }
    }

    /** Arrow keys, OK and Back go to Ember's own screen; Home goes to Ember's Home. */
    private fun key(name: String): String {
        if (name == "home") {
            // Home leaves the player first, then shows Ember's Home.
            if (PlayerRemote.now() != null) PlayerRemote.command("stop", null)
            return if (main { RemoteBridge.screen?.home() } != null) ok() else RemoteServer.error("Open Ember on the TV first.")
        }
        val code = RemoteFormat.keyCode(name) ?: return bad()
        return if (RemoteBridge.press(code)) ok() else RemoteServer.error("The arrows work while Ember is on the TV screen, not inside other apps.")
    }
}

/** The pure parts of the remote's answers, kept apart to test. */
internal object RemoteFormat {
    fun refOf(m: Meta): String {
        val key = listOf(m.type, m.id, m.season?.toString().orEmpty(), m.episode?.toString().orEmpty()).joinToString(":")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(14)
    }

    fun prompt(p: app.ember.tv.player.RemotePrompt): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(p.kind)); put("title", JsonPrimitive(p.title)); put("text", JsonPrimitive(p.text))
        put("choices", buildJsonArray { p.choices.forEach { (id, label) -> add(buildJsonObject { put("id", JsonPrimitive(id)); put("label", JsonPrimitive(label)) }) } })
    }

    /** A list of choices for the phone: each label and whether it is in use. */
    fun choices(list: List<Pair<String, Boolean>>): JsonArray = buildJsonArray {
        list.take(40).forEach { (label, on) -> add(buildJsonObject { put("label", JsonPrimitive(label)); put("on", JsonPrimitive(on)) }) }
    }

    /** The same for every episode of a show, to mark what is playing in a list. */
    fun titleRef(m: Meta): String = refOf(m.copy(season = null, episode = null))

    fun subtitle(m: Meta): String = listOfNotNull(
        when (m.type) { "movie" -> "Movie"; "series" -> "Series"; "tv", "channel" -> "Live"; else -> m.type.replaceFirstChar { it.uppercase() } },
        m.releaseInfo?.takeIf { it.isNotBlank() },
        if (m.season != null && m.episode != null) "S${m.season}:E${m.episode}" else null,
    ).joinToString(" · ")

    /** Only pictures the phone can load itself. */
    fun web(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true)

    /** The TV's order: media server copies first, then addon sources. */
    fun <T> sources(server: List<T>, addon: List<T>): List<T> = server + addon

    fun keyCode(name: String): Int? = when (name) {
        "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
        "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        "ok" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
        "back" -> android.view.KeyEvent.KEYCODE_BACK
        "menu" -> android.view.KeyEvent.KEYCODE_MENU
        else -> null
    }
}
