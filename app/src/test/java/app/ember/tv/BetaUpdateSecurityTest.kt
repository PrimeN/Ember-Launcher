package app.ember.tv

import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Updater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BetaUpdateSecurityTest {
    private val betaApi get() = "https://api.github.com/repos/${BuildConfig.BETA_UPDATE_REPO}/releases/"
    @Test fun standardAndLegacyBuildChoicesUsePublicReleasesWhileBetaUsesPrivateBuilds() {
        for (channel in listOf("releases", "builds", "unknown")) {
            assertEquals(BuildConfig.UPDATE_REPO, Updater.repository(channel))
            assertEquals("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest", Updater.updateUrl(channel))
        }
        assertEquals(BuildConfig.BETA_UPDATE_REPO, Updater.repository("beta"))
        assertEquals(betaApi + "tags/beta-build", Updater.updateUrl("beta"))
        assertTrue(ApiKeys.ALL_IDS.contains("GITHUB_BETA_TOKEN"))
        assertEquals("", ApiKeys.builtIn("GITHUB_BETA_TOKEN"))
    }
    @Test fun betaCredentialsAreScopedToTheConfiguredPrivateReleaseApi() {
        val authorized = Updater.request(betaApi+"assets/123", "application/octet-stream", "fixture-token")
        assertEquals("Bearer fixture-token", authorized.header("Authorization"))
        assertEquals("no-store", authorized.header("Cache-Control"))
        for (url in listOf("https://example.test/private.apk", "https://release-assets.githubusercontent.com/asset", "http://api.github.com/repos/${BuildConfig.BETA_UPDATE_REPO}/releases/assets/1", "https://api.github.com/repos/another/repo/releases/assets/1", betaApi.replace("/releases/", "-other/releases/"))) {
            assertNull(Updater.request(url,"application/octet-stream","fixture-token").header("Authorization"))
        }
        assertNull(Updater.request(betaApi+"assets/123","application/octet-stream").header("Authorization"))
        assertEquals("Bearer fixture-token",Updater.request(betaApi.lowercase()+"assets/123","application/octet-stream","fixture-token").header("Authorization"))
    }
    @Test fun privateDownloadsUseTheAuthenticatedAssetApiAndRetainIntegrityData() {
        val data=Json.parseToJsonElement("""{"tag_name":"beta-build","body":"versionCode: 42","assets":[{"name":"app-42.apk","size":100,"digest":"sha256:${"a".repeat(64)}","url":"${betaApi}assets/123","browser_download_url":"https://github.com/private/repo/releases/download/beta-build/app.apk"}]}""").jsonObject
        val release=Updater.releaseOf(data,true)!!
        assertEquals(betaApi+"assets/123",release.assetApiUrl)
        assertTrue(release.privateBeta);assertEquals(42,release.versionCode)
        assertNull(Updater.verify(release,100,"a".repeat(64)))
        assertNotNull(Updater.verify(release,99,"a".repeat(64)))
    }
    @Test fun untrustedOrMissingPrivateAssetEndpointsAreRejected() {
        for (url in listOf("https://example.test/app.apk", betaApi+"assets/not-an-id", "https://api.github.com/repos/other/repo/releases/assets/123")) {
            val data=Json.parseToJsonElement("""{"assets":[{"name":"app.apk","url":"$url","browser_download_url":"https://example.test/app.apk"}]}""").jsonObject
            assertNull(Updater.releaseOf(data,true))
        }
    }
    @Test fun malformedTokensProduceARedactedValidationError() {
        val token="fixture-secret\ninvalid"
        val error=assertThrows(IllegalArgumentException::class.java) { Updater.request(betaApi+"assets/123","application/octet-stream",token) }
        assertFalse(error.message.orEmpty().contains("fixture-secret"))
    }
}
