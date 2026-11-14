package app.ember.tv

import android.app.Application
import android.content.Context
import android.media.MediaRouter
import app.ember.tv.player.OutputRouteMonitor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class OutputRouteMonitorTest {
    @Test fun connectedBluetoothDoesNotOverrideTheSelectedTvOutput() {
        val app=RuntimeEnvironment.getApplication()
        val router=app.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        shadowOf(router).addBluetoothRoute()
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,router.defaultRoute)
        val seen=mutableListOf<Boolean>()
        OutputRouteMonitor(app) {seen+=it}.use {monitor->
            assertFalse(monitor.bluetooth);assertFalse(seen.last())
            val bluetooth=(0 until router.routeCount).map(router::getRouteAt).first {it.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH}
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,bluetooth)
            assertTrue(monitor.bluetooth);assertTrue(seen.last())
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,router.defaultRoute)
            assertFalse(monitor.bluetooth);assertFalse(seen.last())
        }
    }
    @Test fun closingTheMonitorStopsPlaybackCallbacks() {
        val app=RuntimeEnvironment.getApplication()
        val router=app.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        shadowOf(router).addBluetoothRoute()
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,router.defaultRoute)
        var calls=0
        val monitor=OutputRouteMonitor(app) {calls++}
        monitor.close();val previous=calls
        val bluetooth=(0 until router.routeCount).map(router::getRouteAt).first {it.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH}
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,bluetooth)
        assertEquals(previous,calls)
    }
    @Test fun unrelatedRouteUpdatesDoNotResetTheCurrentOutputsManualTiming() {
        val app=RuntimeEnvironment.getApplication()
        val router=app.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,router.defaultRoute)
        var calls=0;var manualDelay=0
        OutputRouteMonitor(app) {calls++;manualDelay=-50}.use {monitor ->
            val initial=calls
            manualDelay=175
            monitor.refresh()
            monitor.refresh()
            assertEquals(initial,calls)
            assertEquals(175,manualDelay)
        }
    }
    @Test fun preferencesAreStoredAndLoadedThroughTheActualSettingsStore() {
        val context=RuntimeEnvironment.getApplication()
        val config=app.ember.tv.player.PlaybackConfig(
            decoderRules=app.ember.tv.player.DecoderRules(network="software"),bluetoothDelayMs=350,
            seekMode="fast",audioEffects=app.ember.tv.player.AudioEffects("warm",2,"swap"),quickShortcuts=listOf("delay","effects"))
        app.ember.tv.data.Prefs(context).playbackConfig=config
        assertEquals(config,app.ember.tv.data.Prefs(context).playbackConfig)
    }

}
