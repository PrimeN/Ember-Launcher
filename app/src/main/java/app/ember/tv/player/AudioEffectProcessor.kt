package app.ember.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.*

/** Shared immutable settings; only the audio thread owns filter history. */
class AudioEffectState { @Volatile var value = AudioEffects() }

/** Five inexpensive peaking filters, with headroom reserved for boosts. */
internal class AudioDsp(private val rate: Int, private val channels: Int, val effects: AudioEffects) {
    private data class Band(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double,
        val z1: DoubleArray, val z2: DoubleArray)
    private val bands = effects.gains.mapIndexedNotNull { i, gain ->
        val db = gain + if (i == 0) effects.bass.coerceIn(0, 6) else 0
        val frequency = listOf(80, 250, 1000, 4000, 10000)[i]
        if (db == 0 || frequency >= rate / 2) return@mapIndexedNotNull null
        val a = 10.0.pow(db / 40.0); val w = 2 * PI * frequency / rate
        val alpha = sin(w) * sinh(ln(2.0) / 2 * w / sin(w)); val a0 = 1 + alpha / a
        Band((1 + alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha * a) / a0,
            -2 * cos(w) / a0, (1 - alpha / a) / a0, DoubleArray(channels), DoubleArray(channels))
    }
    private val compressor = NightListening.preset(effects.compression)?.let { NightCompressor(rate, it) }
    private val headroom = 10.0.pow(-effects.headroomDb() / 20.0)
    fun frame(samples: FloatArray) {
        for (c in 0 until channels) {
            var x = (if (samples[c].isFinite()) samples[c] else 0f) * headroom
            for (index in bands.indices) {
                val b = bands[index]
                val y = b.b0 * x + b.z1[c]
                b.z1[c] = b.b1 * x - b.a1 * y + b.z2[c]
                b.z2[c] = b.b2 * x - b.a2 * y
                x = y
            }
            samples[c] = x.coerceIn(-1.0, 1.0).toFloat()
        }
        when (effects.channels) {
            "mono" -> { var total = 0f; for (c in 0 until channels) total += samples[c]; val mono = total / channels; samples.fill(mono) }
            "swap" -> if (channels >= 2) { val left = samples[0]; samples[0] = samples[1]; samples[1] = left }
        }
        compressor?.frame(samples)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioEffectProcessor(private val settings: AudioEffectState) : BaseAudioProcessor() {
    private var dsp: AudioDsp? = null
    private var frame = FloatArray(0)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount !in 1..8 || inputAudioFormat.sampleRate <= 0) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }
    override fun onFlush() { dsp = null; frame = FloatArray(inputAudioFormat.channelCount.coerceAtLeast(0)) }
    override fun onReset() { dsp = null; frame = FloatArray(0) }
    override fun queueInput(inputBuffer: ByteBuffer) {
        // #384: the pipeline also queues empty input; before the first real buffer our output is the
        // shared empty buffer, the same object, and copying it into itself throws.
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val effects = settings.value
        if (!effects.active) { output.put(inputBuffer); output.flip(); return }
        val channels = inputAudioFormat.channelCount
        if (dsp?.effects != effects) dsp = AudioDsp(inputAudioFormat.sampleRate, channels, effects)
        val floating = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = channels * if (floating) 4 else 2
        while (inputBuffer.remaining() >= bytes) {
            for (c in 0 until channels) frame[c] = if (floating) inputBuffer.float else inputBuffer.short / 32768f
            dsp!!.frame(frame)
            for (c in 0 until channels) if (floating) output.putFloat(frame[c]) else output.putShort((frame[c] * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }
}
