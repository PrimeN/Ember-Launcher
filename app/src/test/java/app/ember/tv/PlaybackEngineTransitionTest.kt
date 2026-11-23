package app.ember.tv

import android.app.Application
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.ember.tv.player.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackEngineTransitionTest {
    private fun intercept(player:ExoPlayer,callback:(String)->Any?):ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) {_,method,args->
        val answer=callback(method.name)
        if(answer!==Unit)answer else try {method.invoke(player,*(args?:emptyArray()))}catch(e:InvocationTargetException){throw e.targetException}
    } as ExoPlayer

    @Test fun failedPrepareReleasesCandidateAndRestoresOriginalPositionAndPause() {
        val context=RuntimeEnvironment.getApplication()
        val original=ExoPlayer.Builder(context).build()
        val candidate=ExoPlayer.Builder(context).build()
        var released=false
        val failing=intercept(candidate) {method->when(method){
            "prepare"->throw IllegalStateException("Cannot prepare backend")
            "release"->{released=true;Unit}
            else->Unit
        }}
        val engine=PlaybackEngine(context,original,{PlaybackConfig(rememberDecoderChoices=false)},compatibilityFactory={_,_,_->failing}) {}
        try {
            engine.configure(EngineSource(listOf("content://media/123"),"Film","key"))
            engine.player.setMediaItem(MediaItem.fromUri("content://media/123"));engine.player.seekTo(42000)
            engine.player.playWhenReady=false
            assertFalse(engine.switch());assertTrue(released)
            assertEquals(42000,engine.player.currentPosition);assertFalse(engine.player.playWhenReady)
            assertEquals("content://media/123",engine.player.currentMediaItem?.localConfiguration?.uri.toString())
        } finally {engine.player.release()}
    }

    @Test fun audioWithoutACompatibilityDecoderNeverDestroysWorkingStandardEngine() {
        val context=RuntimeEnvironment.getApplication()
        val original=ExoPlayer.Builder(context).build()
        val standard=intercept(original) {method->if(method=="getAudioFormat")Format.Builder().setSampleMimeType("audio/mhm1").build() else Unit}
        var constructed=false
        val engine=PlaybackEngine(context,standard,{PlaybackConfig(rememberDecoderChoices=false)},compatibilityFactory={_,_,_->constructed=true;error("Should not switch")}) {}
        try {
            engine.configure(EngineSource(listOf("content://media/123"),"Film","key"))
            engine.player.setMediaItem(MediaItem.fromUri("content://media/123"));engine.player.seekTo(42000)
            assertFalse(engine.recover(4003));assertFalse(constructed);assertEquals(42000,original.currentPosition)
        } finally {engine.player.release()}
    }
    @Test fun pendingKeySeeksAccumulateButExplicitJumpsAndSourceChangesCancelThem() {
        val context=RuntimeEnvironment.getApplication()
        val actual=ExoPlayer.Builder(context).build()
        val engine=PlaybackEngine(context,actual,{PlaybackConfig(rememberDecoderChoices=false)}) {}
        try {
            engine.configure(EngineSource(listOf("content://media/123"),"Film","key"))
            engine.player.setMediaItem(MediaItem.fromUri("content://media/123"));engine.player.seekTo(10000)
            engine.navigate(20000,true);engine.navigate(30000,true)
            assertEquals(30000L,engine.player.currentPosition);assertEquals(10000L,actual.currentPosition)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
            assertEquals(30000L,actual.currentPosition)
            engine.navigate(90000,true);engine.navigate(12345,false,coalesce=false)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
            assertEquals(12345L,actual.currentPosition)
            engine.navigate(90000,true);engine.player.seekTo(7000)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
            assertEquals(7000L,actual.currentPosition)
            engine.navigate(90000,true)
            engine.configure(EngineSource(listOf("content://media/456"),"Next","next"))
            engine.player.setMediaItem(MediaItem.fromUri("content://media/456"));engine.player.seekTo(5000)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
            assertEquals(5000L,actual.currentPosition)
        }finally{engine.player.release()}
    }

    /** #312: the player that worked is remembered per title and forgotten after it fails. */
    @Test fun workingEngineIsRememberedAndForgotten() {
        val health=DecoderHealth.load(RuntimeEnvironment.getApplication())
        health.rememberEngine("title-key","compatibility")
        assertEquals("compatibility",health.engine("title-key"))
        health.rememberEngine("title-key","bogus")
        assertEquals("compatibility",health.engine("title-key"))
        health.forgetEngine("title-key")
        assertNull(health.engine("title-key"))
    }

    /** #312: release titles with codec endings do not force Compatibility playback. */
    @Test fun releaseTitlesDoNotPickTheEngine() {
        assertFalse(PlaybackEnginePolicy.nativeFirst("https://debrid.example/d/abc","Movie.2020.WEB-DL.DDP5.1.H.264"))
        assertFalse(PlaybackEnginePolicy.nativeFirst("https://debrid.example/d/abc","Show.S01E01.2160p.HEVC"))
        assertTrue(PlaybackEnginePolicy.nativeFirst("https://debrid.example/d/abc","Old.Film.wmv"))
        assertTrue(PlaybackEnginePolicy.nativeFirst("https://example.com/clip.h266"))
    }

    @Test fun unsupportedAudioDoesNotBlockALaterSupportedTrackSwitch() {
        val context=RuntimeEnvironment.getApplication()
        val original=ExoPlayer.Builder(context).build();val candidate=ExoPlayer.Builder(context).build()
        var mime="audio/mhm1";var constructed=false
        val standard=intercept(original){name->if(name=="getAudioFormat")Format.Builder().setSampleMimeType(mime).build() else Unit}
        val engine=PlaybackEngine(context,standard,{PlaybackConfig(rememberDecoderChoices=false)},compatibilityFactory={_,_,_->constructed=true;candidate}) {}
        try {
            engine.configure(EngineSource(listOf("content://media/123"),"Film","key"))
            original.setMediaItem(MediaItem.fromUri("content://media/123"));original.seekTo(42000);original.playWhenReady=false
            assertFalse(engine.switch());assertFalse(constructed)
            mime="audio/ac3"
            assertTrue(engine.switch());assertTrue(constructed);assertFalse(engine.player.playWhenReady)
        }finally{engine.player.release();if(!constructed)candidate.release()}
    }

    @Test fun aFileStandardCannotReadGoesToCompatibilityWhateverTheDecoderPreferences() {
        // #383: an MKV with zlib-compressed tracks fails in the extractor, before any decoder is chosen.
        val context=RuntimeEnvironment.getApplication()
        val original=ExoPlayer.Builder(context).build();val candidate=ExoPlayer.Builder(context).build()
        var constructed=false
        val standard=intercept(original){name->if(name=="getAudioFormat")Format.Builder().setSampleMimeType("audio/ac3").build() else Unit}
        val engine=PlaybackEngine(context,standard,{PlaybackConfig(rememberDecoderChoices=false,ffmpegVideo="always")},
            compatibilityFactory={_,_,_->constructed=true;candidate}) {}
        try {
            engine.configure(EngineSource(listOf("https://debrid.example/d/abc"),"Film","key"))
            original.setMediaItem(MediaItem.fromUri("https://debrid.example/d/abc"))
            assertFalse(engine.recover(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED));assertFalse(constructed)
            assertTrue(engine.recover(androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED));assertTrue(constructed)
        }finally{engine.player.release();if(!constructed)candidate.release()}
    }
}
