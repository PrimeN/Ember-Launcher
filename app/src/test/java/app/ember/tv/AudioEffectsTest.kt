package app.ember.tv

import app.ember.tv.player.*
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@androidx.annotation.OptIn(UnstableApi::class)
class AudioEffectsTest {
    @Test fun swappingOnlyChangesFrontLeftAndRightInSurroundAudio() {
        val samples=floatArrayOf(.1f,.2f,.3f,.4f,.5f,.6f)
        AudioDsp(48000,6,AudioEffects(channels="swap")).frame(samples)
        assertArrayEquals(floatArrayOf(.2f,.1f,.3f,.4f,.5f,.6f),samples,.00001f)
    }
    @Test fun monoKeepsBothSidesAudibleWithoutDoublingThePeak() {
        val samples=floatArrayOf(.9f,-.3f)
        AudioDsp(48000,2,AudioEffects(channels="mono")).frame(samples)
        assertArrayEquals(floatArrayOf(.3f,.3f),samples,.00001f)
    }
    private fun level(effects:AudioEffects,frequency:Double):Double {
        val dsp=AudioDsp(48000,1,effects);val frame=FloatArray(1);var energy=0.0
        repeat(24000) {i->
            frame[0]=(.2*sin(2*PI*frequency*i/48000)).toFloat();dsp.frame(frame)
            if(i>12000)energy+=frame[0]*frame[0]
            assertTrue(frame[0].isFinite() && abs(frame[0])<=1)
        }
        return sqrt(energy/11999)
    }
    @Test fun dialoguePresetEmphasizesSpeechFrequenciesRelativeToBass() {
        assertTrue(level(AudioEffects("dialogue"),4000.0)>level(AudioEffects("dialogue"),80.0)*1.6)
    }
    @Test fun bassControlChangesLowFrequenciesWhileReservingHeadroom() {
        assertTrue(level(AudioEffects(bass=6),80.0)>level(AudioEffects(bass=6),4000.0)*1.5)
        assertTrue(level(AudioEffects("bright",6),10000.0)<.2)
    }
    @Test fun processorLeavesDisabledPcmBytesUnchangedAndCanEnableEffectsLive() {
        val state=AudioEffectState();val processor=AudioEffectProcessor(state)
        processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush()
        fun input()=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putShort(1000).putShort(-2000).also {it.flip()}
        processor.queueInput(input());val original=processor.output.order(ByteOrder.nativeOrder())
        assertEquals(1000,original.short.toInt());assertEquals(-2000,original.short.toInt())
        state.value=AudioEffects(channels="swap")
        processor.queueInput(input());val swapped=processor.output.order(ByteOrder.nativeOrder())
        assertEquals(-2000,swapped.short.toInt());assertEquals(1000,swapped.short.toInt())
    }
    @Test fun emptyInputBeforeTheFirstBufferIsIgnored() {
        // #384: the pipeline queues the shared empty buffer, which is also a fresh processor's output.
        val processor=AudioEffectProcessor(AudioEffectState())
        processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
    }
}
