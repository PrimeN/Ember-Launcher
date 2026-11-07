package app.ember.tv

import app.ember.tv.mediaserver.tokenParamName
import org.junit.Assert.assertEquals
import org.junit.Test

/** Jellyfin's supported sign-in on URLs (#93). */
class ServerAuthTest {
    @Test fun jellyfinUsesApiKeyAndEmbyKeepsItsOwn() {
        assertEquals("ApiKey", tokenParamName("jellyfin"))
        assertEquals("api_key", tokenParamName("emby"))
    }
}
