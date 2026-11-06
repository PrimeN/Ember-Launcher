package app.ember.tv

import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Updater
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class BetaUpdateSecurityTest {
    @get:Rule val temp = TemporaryFolder()
    private val api = "https://api.github.com/repos/PrimeN/Ember-Launcher/releases/"
    private val bytes = "update payload".toByteArray()
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(tag: String = "beta-build", hash: String = digest) = """{
        "tag_name":"$tag","body":"versionCode: 216100000","assets":[{
          "name":"ember-tv-0.9.5.1-216100000.apk","size":${bytes.size},"digest":"sha256:$hash",
          "url":"${api}assets/123",
          "browser_download_url":"https://github.com/PrimeN/Ember-Launcher/releases/download/$tag/app.apk"}]}"""
    private fun client(reply: (Request) -> Pair<Int, ByteArray>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("response")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun betaAndSavedChannelsUsePublicEndpointsWithoutAnApiKeySetting() {
        for (channel in listOf("releases", "builds", "unknown", "beta")) {
            assertEquals("PrimeN/Ember-Launcher", Updater.repository(channel))
            val expected = if (channel == "beta") "tags/beta-build" else "latest"
            assertEquals(api + expected, Updater.updateUrl(channel))
        }
        assertFalse(ApiKeys.ALL_IDS.contains("GITHUB_BETA_TOKEN"))
        assertFalse(ApiKeys.ALL_IDS.contains("GITHUB_TOKEN"))
    }

    @Test fun bothChannelsDiscoverAndDownloadAnUpdateWithoutCredentials() = runBlocking {
        for (channel in listOf("releases", "builds", "beta")) {
            val requests = mutableListOf<Request>()
            val tag = if (channel == "beta") "beta-build" else "v0.9.5.1"
            val http = client { request ->
                requests += request
                200 to if (request.url.host == "api.github.com") release(tag).toByteArray() else bytes
            }
            val found = Updater.check(channel, http).getOrThrow()!!
            val progress = mutableListOf<Float>()
            val file = Updater.download(PersistenceContext(temp.newFolder()), found, progress::add, http).getOrThrow()
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(216100000, found.versionCode)
            assertEquals(digest, found.sha256)
            assertEquals(Updater.updateUrl(channel), requests[0].url.toString())
            assertEquals("https://github.com/PrimeN/Ember-Launcher/releases/download/$tag/app.apk", requests[1].url.toString())
            assertEquals(listOf(1f), progress)
            requests.forEach {
                assertNull(it.header("Authorization")); assertNull(it.header("Cookie"))
                assertEquals("no-store", it.header("Cache-Control"))
            }
        }
    }

    @Test fun downloadsCannotSelectAnUnrelatedRepositoryOrHost() {
        for (url in listOf("https://example.test/app.apk", "https://github.com/other/repo/releases/download/beta-build/app.apk",
            "http://github.com/PrimeN/Ember-Launcher/releases/download/beta-build/app.apk",
            "https://api.github.com/repos/other/repo/releases/assets/123", "${api}assets/not-an-id")) {
            val data = Json.parseToJsonElement("""{"assets":[{"name":"app.apk","url":"$url","browser_download_url":"$url"}]}""").jsonObject
            assertNull(Updater.releaseOf(data))
        }
    }

    @Test fun missingBuildsAndRequestLimitsDoNotAskForPrivateCredentials() = runBlocking {
        for (code in listOf(401, 403, 404, 429)) {
            val result = Updater.check("beta", client { code to "{}".toByteArray() })
            if (code == 404) { assertTrue(result.isSuccess); assertNull(result.getOrNull()) }
            else {
                assertTrue(result.isFailure)
                val message = result.exceptionOrNull()?.message.orEmpty()
                assertFalse(message.contains("token", ignoreCase=true))
                assertFalse(message.contains("API keys", ignoreCase=true))
                assertTrue(message.contains("later"))
            }
        }
    }

    @Test fun corruptedPublicBetaDownloadsAreDeleted() = runBlocking {
        val context = PersistenceContext(temp.newFolder())
        val found = Updater.releaseOf(Json.parseToJsonElement(release(hash="0".repeat(64))).jsonObject)!!
        val result = Updater.download(context, found, {}, client { 200 to bytes })
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("checksum"))
        assertTrue(java.io.File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
    }
}
