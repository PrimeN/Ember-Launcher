package app.ember.tv

import android.app.Application
import app.ember.tv.data.Prefs
import app.ember.tv.data.Updater
import app.ember.tv.state.UpdatesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Beta updates switch: beta checks the beta branch's build, stable checks main's channel. */
class UpdateBranchTest {
    @get:Rule val temp = TemporaryFolder()
    private fun prefs() = Prefs(PersistenceContext(temp.newFolder()))
    private fun release(name: String) = Updater.Release("v$name", name, "", "https://example.test/asset", "app.apk", 1, Int.MAX_VALUE)

    @Test fun theSwitchChoosesTheBranchEachCheckReads() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = prefs()
        val state = UpdatesState(Application(), prefs, scope)
        val asked = mutableListOf<String>()
        state.checkRelease = { channel -> asked += channel; Result.success(release(channel)) }
        try {
            state.updateUpdateChannel("builds")
            assertEquals("builds", state.activeChannel)
            state.toggleUpdateBeta()
            assertTrue(state.updateBeta && prefs.updateBeta)
            assertEquals("beta", state.activeChannel)
            assertEquals("beta", state.update?.name)
            state.toggleUpdateBeta()
            assertEquals("builds", state.activeChannel)
            assertEquals("builds", state.update?.name)
            assertEquals(listOf("builds", "beta", "builds"), asked)
            // The stable choice is kept while beta is on and comes back unchanged.
            assertEquals("builds", prefs.updateChannel)
        } finally { scope.cancel() }
    }

    @Test fun anOfferFromTheOtherBranchIsDroppedWhenSwitching() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = UpdatesState(Application(), prefs(), scope)
        val pending = kotlinx.coroutines.CompletableDeferred<Result<Updater.Release?>>()
        state.checkRelease = { if (it == "beta") pending.await() else Result.success(release("Stable")) }
        try {
            state.checkForUpdate(true); assertEquals("Stable", state.update?.name)
            state.toggleUpdateBeta()
            assertNull(state.update); assertEquals("Checking...", state.updateMessage)
        } finally { scope.cancel() }
    }

    @Test fun theEarlierBetaChannelChoiceBecomesTheSwitch() {
        val prefs = prefs()
        assertFalse(prefs.updateBeta)
        prefs.updateChannel = "beta"
        assertTrue(prefs.updateBeta)
        assertEquals("builds", prefs.updateChannel)
        prefs.updateBeta = false
        assertFalse(prefs.updateBeta)
    }
}
