package app.ember.tv

import org.junit.Assert.*
import org.junit.Test

/** #255, #256 */
class RemoteGapAndSubtitlesTest {
    @Test fun theInstalledRemoteDoesNotLeaveABandUnderTheTabs() {
        val page = app.ember.tv.remote.RemotePage.app("TV", "#FF7A2F")
        // A solid status bar keeps iOS from shortening the page; anything iOS still leaves shows the tab bar's colour.
        assertTrue(page.contains("apple-mobile-web-app-status-bar-style content=black>")); assertFalse(page.contains("black-translucent"))
        assertTrue(page.contains("html,body{margin:0;height:100%;background:#16171B;"))
        assertTrue(page.contains(".app{position:fixed;inset:0;display:flex;flex-direction:column;background:var(--bg)}"))
    }

    @Test fun aSubtitleThatFailsToLoadCannotEndPlayback() {
        val controller = java.io.File("src/main/java/app/ember/tv/player/SubtitleController.kt").readText()
        assertTrue(controller.contains("lifecycleScope.launch(kotlinx.coroutines.CoroutineExceptionHandler"))
        assertTrue(controller.contains("catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }"))
        val download = java.io.File("src/main/java/app/ember/tv/data/Subtitles.kt").readText()
        assertTrue(download.contains("catch (_: Throwable) { null }"))
    }
}
