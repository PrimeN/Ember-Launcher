package app.ember.tv

import app.ember.tv.mediaserver.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerUsersAndListsTest {
    private class Secrets : ServerSecrets {
        val values = mutableMapOf<String, String>()
        var writable = true
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String): Boolean {
            if (!writable) return false
            values[key] = value; return true
        }
        override fun remove(key: String) { values.remove(key) }
    }
    private fun http(reply: (Request) -> Pair<Int, String>) = ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody()).build()
    }.build())

    @Test fun plexHomeUsersAcceptJsonAndXmlAndPreservePinRequirements() {
        val xml = PlexUsers.users("""<MediaContainer><User id="1" title="Owner &amp; family" protected="1"/><User id="2" title="Guest" protected="0"/></MediaContainer>""")
        assertEquals("Owner & family", xml.first().name); assertTrue(xml.first().protected); assertFalse(xml.last().protected)
        val json = PlexUsers.users("""{"users":[{"id":1,"title":"Owner","protected":true},{"id":2,"username":"Guest","protected":false}]}""")
        assertEquals(listOf("1", "2"), json.map { it.id }); assertTrue(json.first().protected)
        assertEquals("fixture-token", PlexUsers.token("""<user authenticationToken="fixture-token"/>"""))
        assertEquals("fixture-token", PlexUsers.token("""{"user":{"authToken":"fixture-token"}}"""))
    }

    @Test fun plexXmlNeverAllowsDoctypesOrExternalEntities() {
        assertThrows(IllegalArgumentException::class.java) {
            PlexUsers.users("""<!DOCTYPE data [<!ENTITY file SYSTEM "file:///not-a-real-file">]><MediaContainer><User id="1" title="&file;"/></MediaContainer>""")
        }
        assertThrows(IllegalArgumentException::class.java) { PlexUsers.users(" ".repeat(512_001)) }
    }

    @Test fun switchingPlexUserUsesOwnerForAuthenticationAndSelectedTokenForResources() = runBlocking {
        val secrets = Secrets(); val accounts = Accounts(secrets); accounts.plexToken = "fixture-owner"
        val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val client = PlexClient(accounts, http { request ->
            requests += request
            200 to when {
                request.url.encodedPath.endsWith("/switch") -> """<user authenticationToken="fixture-child"/>"""
                request.url.encodedPath == "/api/home/users" -> """<MediaContainer><User id="2" title="Child" protected="1"/></MediaContainer>"""
                else -> "[]"
            }
        })
        val user = client.homeUsers().single()
        val media = MediaServers(accounts, client)
        val ownerIdentity = media.accountFingerprint()
        val session = client.authenticateHomeUser(user, "1234")
        assertEquals("fixture-owner", accounts.plexAccessToken)
        client.selectHomeUser(session)
        assertNotEquals(ownerIdentity, media.accountFingerprint())
        assertEquals("fixture-child", accounts.plexAccessToken)
        assertEquals("fixture-owner", accounts.plexToken)
        assertEquals("Child", accounts.plexUserName)
        client.servers(force = true)
        val auth = requests.single { it.url.encodedPath.endsWith("/switch") }
        assertEquals("POST", auth.method); assertEquals("1234", auth.url.queryParameter("pin"))
        assertEquals("fixture-owner", auth.header("X-Plex-Token"))
        assertEquals("fixture-child", requests.last().header("X-Plex-Token"))
        assertTrue(secrets.values.containsKey("plex.home"))
    }

    @Test fun failedPlexPinAndFailedEncryptedWriteKeepThePreviousUser() = runBlocking {
        val secrets = Secrets(); val accounts = Accounts(secrets); accounts.plexToken = "fixture-owner"
        val client = PlexClient(accounts, http { 403 to "{}" })
        client.selectHomeUser(PlexHomeSession("1", "Previous", "fixture-previous"))
        try { client.authenticateHomeUser(ServerUser("2", "Next", true), "0000"); fail("PIN failure must not authorize") }
        catch (e: ServerHttpException) { assertEquals(403, e.status) }
        assertEquals("Previous", accounts.plexUserName)
        secrets.writable = false
        assertThrows(IllegalStateException::class.java) { client.selectHomeUser(PlexHomeSession("2", "Next", "fixture-next")) }
        assertEquals("fixture-previous", accounts.plexAccessToken)
        assertEquals("Previous", accounts.plexUserName)
    }

    @Test fun plexWatchlistUsesCloudIdentityAndDoesNotPersistAuthenticatedArtworkUrls() {
        val items = PlexPersonalLists.items(Json.parseToJsonElement("""{"MediaContainer":{"Metadata":[{"ratingKey":"10","type":"movie","title":"Film","year":2020,"thumb":"https://example.com/image?X-Plex-Token=fixture-private&w=300","Guid":[{"id":"imdb://tt10"},{"id":"tmdb://100"}]},{"ratingKey":"20","type":"show","title":"Show"}]}}"""))
        assertEquals(listOf("movie", "series"), items.map { it.type })
        assertEquals("plexwatchlist:10", items.first().id)
        assertEquals("tt10", items.first().imdb); assertEquals(100L, items.first().tmdbId)
        assertNull(items.first().serverRef)
        assertFalse(items.first().poster!!.contains("fixture-private"))
        assertEquals("Plex", items.first().progressSource)
    }

    @Test fun plexWatchlistRemovalUsesPutAndTheSelectedUser() = runBlocking {
        val accounts = Accounts(Secrets()); accounts.plexToken = "fixture-owner"
        var request: Request? = null
        val client = PlexClient(accounts, http { request = it; 200 to "{}" })
        client.selectHomeUser(PlexHomeSession("2", "Guest", "fixture-guest"))
        client.removeFromWatchlist("100")
        assertEquals("PUT", request!!.method)
        assertEquals("100", request!!.url.queryParameter("ratingKey"))
        assertEquals("fixture-guest", request!!.header("X-Plex-Token"))
    }

    @Test fun jellyfinUserLoginRecordsActualNameWithoutSavingThePassword() = runBlocking {
        val accounts = Accounts(Secrets())
        val client = JellyfinClient(accounts, http { request -> 200 to when (request.url.encodedPath) {
            "/Users/AuthenticateByName" -> """{"AccessToken":"fixture-session","User":{"Id":"u2","Name":"Actual name"}}"""
            else -> """{"ServerName":"Home server"}"""
        } })
        val account = client.login("jellyfin", "http://192.168.1.2:8096", "typed-user", "fixture-password")
        assertEquals("Actual name", account.userName); assertEquals("u2", account.userId)
        accounts.saveServers(listOf(account))
        assertEquals(account, accounts.servers().single())
        assertFalse(Json.encodeToString(ServerAccount.serializer(), account).contains("fixture-password"))
    }

    @Test fun serverFavoritesIncludeShowsAndMoviesAsDistinctWatchlistRows() = runBlocking {
        val accounts = Accounts(Secrets())
        val a = ServerAccount("account", "jellyfin", "Home", "http://192.168.1.2:8096", "user", "fixture-session")
        val client = JellyfinClient(accounts, http { request -> 200 to when {
            request.url.queryParameter("Filters") == "IsFavorite" -> """{"Items":[{"Id":"m","Name":"Film","Type":"Movie"},{"Id":"s","Name":"Show","Type":"Series"}]}"""
            request.url.encodedPath.endsWith("/Latest") -> "[]"
            else -> """{"Items":[]}"""
        } })
        val row = client.rows(a).single()
        assertTrue(row.id.endsWith(":watchlist"))
        assertEquals(listOf("movie", "series"), row.items.map { it.type })
        assertEquals("jellyfin|account|s", row.items.last().serverRef)
    }

    @Test fun olderServerAccountsStillDecodeWithoutUserNames() {
        val account = Json.decodeFromString(ServerAccount.serializer(), """{"key":"a","kind":"emby","name":"Server","url":"http://192.168.1.2","userId":"u","token":"fixture-session"}""")
        assertEquals("", account.userName)
    }

    @Test fun unreadableSelectedPlexSessionNeverFallsBackToOwnerAccess() {
        val secrets = Secrets(); val accounts = Accounts(secrets); accounts.plexToken = "fixture-owner"
        secrets.values["plex.home"] = "{damaged"
        assertNull(accounts.plexAccessToken)
        assertEquals("User unavailable", accounts.plexUserName)
        assertEquals("fixture-owner", accounts.plexToken)
    }
}
