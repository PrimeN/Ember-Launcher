package app.ember.tv.player

import android.app.Application
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleTickerTest {
    @Test fun callbacksStopWhenPausedOrInactiveButRefreshStillRendersASeek() {
        var playing=false;var active=false;var foreground=true;var renders=0
        val ticker=SubtitleTicker(Handler(Looper.getMainLooper()),{foreground},{playing&&active}){renders++}
        val looper=shadowOf(Looper.getMainLooper())
        ticker.refresh();looper.idleFor(1,TimeUnit.SECONDS);assertEquals(1,renders)
        playing=true;ticker.refresh();looper.idleFor(1,TimeUnit.SECONDS);assertEquals(2,renders)
        active=true;ticker.refresh();looper.idleFor(1,TimeUnit.SECONDS);assertTrue(renders>3)
        playing=false;ticker.refresh();val paused=renders
        looper.idleFor(1,TimeUnit.SECONDS);assertEquals(paused,renders)
        ticker.refresh();assertEquals(paused+1,renders)
        looper.idleFor(1,TimeUnit.SECONDS);assertEquals(paused+1,renders)
        playing=true;ticker.refresh();foreground=false
        val stopped=renders;looper.idleFor(1,TimeUnit.SECONDS);assertEquals(stopped,renders)
    }
}
