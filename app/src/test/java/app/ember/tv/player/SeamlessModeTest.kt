package app.ember.tv.player

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** "Only if seamless" in Compatibility: only the display's own seamless rates, never a resolution change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SeamlessModeTest {
    private fun mode(id:Int,width:Int,height:Int,rate:Float)=ReflectionHelpers.callConstructor(android.view.Display.Mode::class.java,
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,id),
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,width),
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,height),
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType,rate))

    @Test fun seamlessMatchingCannotSelectAResolutionChangeOrAnUnlistedRefreshRate() {
        val current=mode(1,1920,1080,60f);val seamless=mode(2,1920,1080,48f)
        val disruptive=mode(3,1920,1080,24f);val resolution=mode(4,3840,2160,24f)
        ReflectionHelpers.setField(current,"mAlternativeRefreshRates",floatArrayOf(48f))
        val modes=listOf(current,seamless,disruptive,resolution)
        assertEquals(2,Playback.modeFor(current,modes,24f,1920,1080,false,true)?.modeId)
        assertEquals(3,Playback.modeFor(current,modes,24f,1920,1080,false,false)?.modeId)
        ReflectionHelpers.setField(current,"mAlternativeRefreshRates",floatArrayOf())
        assertNull(Playback.modeFor(current,modes,24f,1920,1080,false,true))
        assertNull(Playback.modeFor(current,modes,Float.NaN,1920,1080,false,false))
    }

    @Test fun remoteDigitsJumpByTenths() {
        assertEquals(0L,PlaybackKeys.position(100_000,0))
        assertEquals(50_000L,PlaybackKeys.position(100_000,5))
        assertNull(PlaybackKeys.position(0,5))
        assertEquals(PlaybackKeys.Action.CAPTIONS,PlaybackKeys.action(android.view.KeyEvent.KEYCODE_CAPTIONS))
        assertEquals(7,PlaybackKeys.digit(android.view.KeyEvent.KEYCODE_NUMPAD_7))
    }
}
