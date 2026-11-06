package app.ember.tv

import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Updater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PublicUpdatesTest {
    @Test fun allBuildsUseThePublicRepositoryWithoutACredentialSetting() {
        assertEquals("PrimeN/Ember-Launcher", BuildConfig.UPDATE_REPO)
        assertFalse(ApiKeys.ALL_IDS.contains("GITHUB_TOKEN"))
        for (channel in listOf("releases", "builds", "beta")) {
            val url = Updater.updateUrl(channel)
            val request = Updater.request(url, "application/vnd.github+json")
            assertEquals("api.github.com", request.url.host)
            assertTrue(request.url.encodedPath.startsWith("/repos/PrimeN/Ember-Launcher/releases/"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
        }
    }

    @Test fun publicDownloadIsPreferredWhileIntegrityAndBuildMetadataAreRetained() {
        val digest = "a".repeat(64)
        val data = Json.parseToJsonElement("""{
          "tag_name":"v0.9.5","name":"Ember TV 0.9.5","body":"versionCode: 42",
          "assets":[{"name":"source.txt","url":"https://example.test/source"},
          {"name":"ember-tv-0.9.5-42-debug.apk","size":123,"digest":"sha256:$digest",
           "url":"https://api.github.com/repos/PrimeN/Ember-Launcher/releases/assets/1",
           "browser_download_url":"https://github.com/PrimeN/Ember-Launcher/releases/download/v0.9.5/ember.apk"}]
        }""").jsonObject
        val release = Updater.releaseOf(data)!!
        assertEquals("https://github.com/PrimeN/Ember-Launcher/releases/download/v0.9.5/ember.apk", release.assetApiUrl)
        assertEquals(42, release.versionCode)
        assertEquals(123L, release.sizeBytes)
        assertEquals(digest, release.sha256)
        assertNull(Updater.request(release.assetApiUrl, "application/octet-stream").header("Authorization"))
    }

    @Test fun publicApiAssetRemainsACompatibleFallback() {
        val data = Json.parseToJsonElement("""{"assets":[{"name":"ember-tv-0.9.5-7.apk","url":"https://api.github.com/repos/PrimeN/Ember-Launcher/releases/assets/1"}]}""").jsonObject
        assertEquals(7, Updater.releaseOf(data)!!.versionCode)
        assertTrue(Updater.releaseOf(data)!!.assetApiUrl.startsWith("https://api.github.com/"))
    }

    @Test fun releasesWithoutADownloadableApkAreEmpty() {
        assertNull(Updater.releaseOf(Json.parseToJsonElement("""{"assets":[]}""").jsonObject))
        assertNull(Updater.releaseOf(Json.parseToJsonElement("""{"assets":[{"name":"source.zip","url":"https://example.test/source"}]}""").jsonObject))
        assertNull(Updater.releaseOf(Json.parseToJsonElement("""{"assets":[{"name":"app.apk"}]}""").jsonObject))
    }
}
