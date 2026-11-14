package app.ember.tv

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import app.ember.tv.player.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackNavigationTest {
    @Test fun basicAndAdvancedSkipControlsUseFastModeWithoutChangingResumeSeekParameters() {
        val calls=mutableListOf<String>()
        var parameters=SeekParameters.EXACT
        val standard=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) {_,method,args->
            when(method.name) {
                "getCurrentPosition"->5000L
                "getDuration"->60000L
                "getSeekBackIncrement"->10000L
                "getSeekForwardIncrement"->10000L
                "getSeekParameters"->parameters
                "setSeekParameters"->{parameters=args!![0] as SeekParameters;null}
                "seekTo"->{calls+="${args!![0]}:${parameters==SeekParameters.CLOSEST_SYNC}";null}
                else->null
            }
        } as ExoPlayer
        PlaybackSeeking.seek(standard,PlaybackSeeking.target(5000,-10000,60000),true)
        assertEquals(listOf("0:true"),calls);assertEquals(SeekParameters.EXACT,parameters)
        PlaybackSeeking.seek(standard,PlaybackSeeking.target(5000,10000,60000),true)
        assertEquals("15000:true",calls.last())
        standard.seekTo(17000L)
        assertEquals("17000:false",calls.last())
        PlaybackSeeking.seek(standard,18000L,false)
        assertEquals("18000:false",calls.last())
        assertEquals(60000L,PlaybackSeeking.target(55000,10000,60000))
    }
}
