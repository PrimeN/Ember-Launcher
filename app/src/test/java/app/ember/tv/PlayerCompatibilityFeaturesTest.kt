package app.ember.tv

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import app.ember.tv.data.SubtitleConfig
import app.ember.tv.player.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerCompatibilityFeaturesTest {
    @Test fun manualSamiAndMicroDvdFilesLoadIntoStandardSubtitleOverlay()=runBlocking {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).get()
        val player=ExoPlayer.Builder(activity).build()
        try {
            val engine=SubtitleEngine(SubtitleView(activity))
            val controller=SubtitleController(activity,{player},engine,SubtitleConfig(),{})
            assertTrue(controller.loadManual("<SAMI><SYNC start=0><P>Hello<SYNC start=1000><P>&nbsp;</SAMI>","sami"))
            assertTrue(engine.active)
            assertTrue(controller.loadManual("{1}{1}25.0\n{25}{50}A scene","microdvd"))
            assertTrue(engine.active)
        } finally {player.release()}
    }
    @Test fun nativeSubtitleRouteReceivesConvertedSrtForSami()=runBlocking {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).get()
        var loaded:Pair<String,String>?=null
        val controller=SubtitleController(activity,{null},SubtitleEngine(SubtitleView(activity)),SubtitleConfig(),{},
            nativeSubtitle={text,format,_->loaded=text to format;true})
        assertTrue(controller.loadManual("<SAMI><SYNC start=0><P>Hello<SYNC start=1000><P>&nbsp;</SAMI>","sami"))
        assertEquals("srt",loaded?.second);assertTrue(loaded!!.first.contains("00:00:00,000 --> 00:00:01,000"))
    }
    private fun format(width:Int=1920,height:Int=1080,transfer:Int=C.COLOR_TRANSFER_SDR)=Format.Builder()
        .setSampleMimeType("video/hevc").setCodecs("hvc1.1.6.L120").setWidth(width).setHeight(height)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()
    @Test fun rememberedSoftwareVideoIsLimitedToSdrAtMost1080p() {
        assertTrue(DecoderHealth.softwareSafe(format()))
        assertFalse(DecoderHealth.softwareSafe(format(3840,2160)))
        assertFalse(DecoderHealth.softwareSafe(format(transfer=C.COLOR_TRANSFER_ST2084)))
        assertFalse(DecoderHealth.softwareSafe(format(transfer=C.COLOR_TRANSFER_HLG)))
        assertFalse(DecoderHealth.softwareSafe(format().buildUpon().setSampleMimeType("video/dolby-vision").build()))
        assertFalse(DecoderHealth.softwareSafe(format().buildUpon().setDrmInitData(androidx.media3.common.DrmInitData(
            androidx.media3.common.DrmInitData.SchemeData(C.WIDEVINE_UUID,"video/mp4",byteArrayOf(1)))).build()))
        assertFalse(DecoderHealth.softwareSafe(Format.Builder().setSampleMimeType("video/hevc").build()))
    }
    @Test fun profileSeparatesHdrAndResolutionAndResetInvalidatesAlreadyLoadedHints() {
        val c:Application=RuntimeEnvironment.getApplication()
        val health=DecoderHealth.load(c);val f=format()
        health.remember(f,"working.decoder",false)
        assertNotNull(health.hint(f))
        assertNotEquals(DecoderHealth.profile(f),DecoderHealth.profile(format(3840,2160)))
        assertNotEquals(DecoderHealth.profile(f),DecoderHealth.profile(format(transfer=C.COLOR_TRANSFER_ST2084)))
        DecoderHealth.clear(c);assertNull(health.hint(f))
    }
}
