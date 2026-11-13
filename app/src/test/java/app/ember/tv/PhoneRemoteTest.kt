package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.remote.RemoteAuth
import app.ember.tv.remote.RemoteFormat
import app.ember.tv.remote.RemotePage
import app.ember.tv.remote.RemoteServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** #241: the phone remote's sign-in, request checks and answers. */
class PhoneRemoteTest {
    private class MemoryStore : RemoteAuth.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun passwordsAreHashedAndWrongGuessesLockSignIn() {
        val store = MemoryStore(); val now = AtomicLong(1_000)
        val auth = RemoteAuth(store, now::get, iterations = 1_000)
        assertFalse(auth.hasPassword); assertTrue("no password: open on the home network", auth.allows(null))
        assertEquals("Use at least 4 characters.", auth.setPassword("abc"))
        assertNull(auth.setPassword("sofa-time"))
        assertFalse("the password itself is never kept", store.map.values.any { it.contains("sofa-time") })
        assertFalse(auth.allows(null)); assertTrue(auth.check("sofa-time"))
        repeat(RemoteAuth.MAX_FAILURES) { assertFalse(auth.check("guess$it")) }
        assertTrue(auth.locked()); assertFalse("locked even for the right one", auth.check("sofa-time"))
        now.addAndGet(RemoteAuth.LOCK_MS + 1); assertTrue(auth.check("sofa-time"))
    }

    @Test fun phonesKeepTokensUntilSignedOut() {
        val store = MemoryStore()
        val auth = RemoteAuth(store, iterations = 1_000)
        auth.setPassword("sofa-time")
        val token = auth.issue()
        assertTrue(auth.valid(token)); assertTrue(auth.allows(token)); assertFalse(auth.valid(token + "x"))
        assertFalse("only a hash of the token is kept", store.map.values.any { it.contains(token) })
        val tokens = (1..RemoteAuth.MAX_PHONES).map { auth.issue() }
        assertFalse("the oldest phone drops out", auth.valid(token)); assertTrue(auth.valid(tokens.last()))
        auth.revoke(tokens.last()); assertFalse(auth.valid(tokens.last()))
        auth.signOutAll(); assertEquals(0, auth.phones)
        val again = auth.issue(); auth.setPassword("new-one")
        assertFalse("a new password signs every phone out", auth.valid(again))
    }

    private fun req(method: String, path: String, host: String? = "192.168.1.20:8642", body: String = "", headers: Map<String, String> = emptyMap(), cookie: String? = null) =
        RemoteServer.Request(method, path, emptyMap(), buildMap { host?.let { put("host", it) }; putAll(headers); cookie?.let { put("cookie", "a=b; ${RemoteServer.COOKIE}=$it") } }, body)

    @Test fun theServerOnlyAnswersItsOwnPageOnItsOwnAddress() {
        val auth = RemoteAuth(MemoryStore(), iterations = 1_000)
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val server = RemoteServer(auth, { name, p -> calls += name to p; if (name == "state" || name == "player") """{"ok":true}""" else null }, { "Living room" })
        val change = mapOf(RemoteServer.HEADER to "1")
        assertEquals(421, server.route(req("GET", "/", host = "evil.example:8642")).status)
        assertEquals(421, server.route(req("GET", "/", host = null)).status)
        assertTrue(server.hostAllowed("10.0.0.5:8642")); assertTrue(server.hostAllowed("localhost:8642")); assertFalse(server.hostAllowed("ember.local:8642"))
        // No password: the app page and its requests work, but changes still need the page's header.
        assertTrue(server.route(req("GET", "/")).body.contains("id=hero"))
        assertEquals(403, server.route(req("POST", "/api/player", body = "action=toggle")).status)
        assertEquals(200, server.route(req("POST", "/api/player", body = "action=toggle", headers = change)).status)
        assertEquals("toggle", calls.last().second["action"])
        assertEquals("changes are never a GET", 405, server.route(req("GET", "/api/player")).status)
        assertEquals(404, server.route(req("POST", "/api/nope", headers = change)).status)
        // With a password: sign in first.
        auth.setPassword("sofa-time")
        assertTrue(server.route(req("GET", "/")).body.contains("id=lf"))
        assertEquals(401, server.route(req("GET", "/api/state")).status)
        assertEquals(401, server.route(req("POST", "/login", body = "password=nope", headers = change)).status)
        val signed = server.route(req("POST", "/login", body = "password=sofa-time", headers = change))
        assertEquals(200, signed.status)
        val cookie = signed.headers.single().substringAfter("${RemoteServer.COOKIE}=").substringBefore(';')
        assertTrue(signed.headers.single().contains("HttpOnly") && signed.headers.single().contains("SameSite=Strict"))
        assertEquals(200, server.route(req("GET", "/api/state", cookie = cookie)).status)
        assertTrue(server.route(req("GET", "/", cookie = cookie)).body.contains("id=hero"))
        assertEquals(200, server.route(req("POST", "/api/logout", headers = change, cookie = cookie)).status)
        assertEquals(401, server.route(req("GET", "/api/state", cookie = cookie)).status)
    }

    @Test fun answersUseShortReferencesAndPlainText() {
        val m = Meta(id = "tt0111161", type = "movie", name = "Film", releaseInfo = "1994")
        assertEquals(RemoteFormat.refOf(m), RemoteFormat.refOf(m.copy(name = "Other name")))
        assertNotEquals(RemoteFormat.refOf(m), RemoteFormat.refOf(m.copy(id = "tt2")))
        val ep = Meta(id = "tt1", type = "series", name = "Show", season = 1, episode = 2)
        assertEquals("every episode marks its show", RemoteFormat.titleRef(ep), RemoteFormat.titleRef(ep.copy(season = 3, episode = 4)))
        assertEquals("Movie · 1994", RemoteFormat.subtitle(m)); assertEquals("Series · S1:E2", RemoteFormat.subtitle(ep))
        assertFalse(RemoteFormat.web("file:///data/x.png")); assertTrue(RemoteFormat.web("https://x/y.jpg"))
        assertEquals(listOf("server", "addon"), RemoteFormat.sources(listOf("server"), listOf("addon")))
        assertNull(RemoteFormat.keyCode("power"))
        val page = RemotePage.app("<Living room>", "#123456\"")
        assertTrue(page.contains("&lt;Living room&gt;") && !page.contains("<Living room>"))
        assertTrue("a bad accent falls back", page.contains("--accent:#FF7A2F"))
        assertFalse("the page draws text, never markup from the TV", Regex("innerHTML=(?!I\\[)").containsMatchIn(page))
        assertTrue(page.contains("'X-Ember-Remote':'1'"))
    }

    @Test fun keysGoToEmbersOwnScreenAndQuestionsReachThePhone() {
        // #242: no system key injection, which apps may not use.
        val controller = java.io.File("src/main/java/app/ember/tv/remote/RemoteController.kt").readText()
        assertFalse(controller.contains("Instrumentation")); assertTrue(controller.contains("RemoteBridge.press(code)"))
        val bridge = java.io.File("src/main/java/app/ember/tv/remote/RemoteBridge.kt").readText()
        assertTrue(bridge.contains("activity.dispatchKeyEvent(down)") && bridge.contains("onBackPressedDispatcher"))
        assertTrue(java.io.File("src/main/java/app/ember/tv/EmberApp.kt").readText().contains("RemoteBridge.install(this)"))
        // #243: the TV's questions, with its own choices.
        val json = RemoteFormat.prompt(app.ember.tv.player.RemotePrompt("resume", "Continue watching", "Film", listOf("resume" to "▶ Resume from 12:30", "start" to "Start over", "cancel" to "Cancel")))
        assertEquals("resume", json["kind"].toString().trim('"'))
        assertEquals(3, (json["choices"] as kotlinx.serialization.json.JsonArray).size)
        listOf("vm.resumeAsk", "vm.playing.chooser", "PlayerRemote.answer(kind, choice)", "resumeDecided = true, startOver = choice == \"start\"").forEach { assertTrue(it, controller.contains(it)) }
        val player = java.io.File("src/main/java/app/ember/tv/player/PlayerRemote.kt").readText()
        assertTrue(player.contains("a.playNext()") && player.contains("a.dismissUpNext()") && player.contains("a.doSkip()"))
        val page = RemotePage.app("TV", "#4F7DF3")
        assertTrue(page.contains("id=ask") && page.contains("api('prompt',{kind:p.kind,choice:c.id},1)"))
    }

    @Test fun theRemoteCanBeKeptOnTheHomeScreenAsAnApp() {
        // #247
        val auth = RemoteAuth(MemoryStore(), iterations = 1_000).apply { setPassword("sofa-time") }
        val server = RemoteServer(auth, { _, _ -> null }, { "Den" }, { "#4F7DF3" }, icon = { size -> ByteArray(size) })
        val manifest = server.route(req("GET", "/manifest.webmanifest"))
        assertEquals("needs no sign-in, holds nothing private", 200, manifest.status)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(manifest.body).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("\"standalone\"", json["display"].toString()); assertEquals("\"/\"", json["start_url"].toString())
        assertTrue(manifest.body.contains("/icon-512.png"))
        assertEquals(192, server.route(req("GET", "/icon-192.png")).bytes!!.size)
        assertEquals(180, server.route(req("GET", "/apple-touch-icon.png")).bytes!!.size)
        assertTrue(server.route(req("GET", "/icon.svg")).body.contains("#4F7DF3"))
        assertEquals("still only on the TV's own address", 421, server.route(req("GET", "/manifest.webmanifest", host = "evil.example")).status)
        val noPictures = RemoteServer(auth, { _, _ -> null })
        assertEquals(404, noPictures.route(req("GET", "/icon-192.png")).status)
        val page = RemotePage.app("Den", "#4F7DF3")
        listOf("rel=manifest", "apple-touch-icon", "viewport-fit=cover", "class=tabs", "id=pad", "touch-action:none", "id=mini", "env(safe-area-inset-bottom)")
            .forEach { assertTrue(it, page.contains(it)) }
        assertTrue(RemotePage.login("Den", "#4F7DF3").contains("rel=manifest"))
    }
}
