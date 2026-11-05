package app.ember.tv

import app.ember.tv.tracker.TokenStore
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class TokenStorageFallbackTest {
    @get:Rule val temp=TemporaryFolder()
    private fun reset() {
        TokenStore::class.java.getDeclaredField("instance").apply {isAccessible=true}.set(null,null)
    }
    @Before fun before()=reset()
    @After fun after()=reset()
    @Test fun unavailableKeystorePreservesExistingLegacyTokensWithoutWritingNewPlaintext() {
        val ctx=PersistenceContext(temp.root)
        val legacy=ctx.getSharedPreferences("ember_tokens_plain",0)
        legacy.edit().putString("old","fixture-existing").commit()
        val store=TokenStore(ctx)
        assertFalse(store.encryptionAvailable)
        assertEquals("fixture-existing",store.get("old"))
        assertFalse(store.putEncrypted("private","fixture-new"))
        assertTrue(runCatching {store.put("new","fixture-new")}.isFailure)
        assertTrue(runCatching {store.putAll(mapOf("access" to "fixture-a","refresh" to "fixture-r"))}.isFailure)
        assertEquals(mapOf("old" to "fixture-existing"),legacy.all)
    }
}
