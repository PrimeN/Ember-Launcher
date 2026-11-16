package app.ember.tv.player

import kotlin.math.*

internal data class CompressionPreset(val thresholdDb:Double,val ratio:Double,val makeupDb:Double) {
    fun filter()="acompressor=threshold=${10.0.pow(thresholdDb/20)}:ratio=$ratio:attack=5:release=250:makeup=${10.0.pow(makeupDb/20)}:knee=2.828427:link=maximum:detection=peak"
}
internal object NightListening {
    val choices=listOf("off" to "Off","gentle" to "Gentle compression","night" to "Night listening")
    fun preset(mode:String):CompressionPreset?=when(mode){"gentle"->CompressionPreset(-18.0,2.0,3.0);"night"->CompressionPreset(-24.0,4.0,6.0);else->null}
}
/** Linked channels preserve the sound image. No additional buffering or lookahead latency. */
internal class NightCompressor(rate:Int,private val preset:CompressionPreset) {
    private val attack=exp(-1.0/(rate*.005));private val release=exp(-1.0/(rate*.250))
    private var envelope=0.0
    fun frame(samples:FloatArray) {
        var peak=0.0
        for(i in samples.indices){if(!samples[i].isFinite())samples[i]=0f;peak=max(peak,abs(samples[i].toDouble()))}
        val coefficient=if(peak>envelope)attack else release
        envelope=coefficient*envelope+(1-coefficient)*peak
        val db=20*log10(envelope.coerceAtLeast(1e-12));val over=db-preset.thresholdDb
        val knee=9.0309
        val reduction=when {over<=-knee/2->0.0;over>=knee/2->(1-1/preset.ratio)*over
            else->(1-1/preset.ratio)*(over+knee/2).pow(2)/(2*knee)}
        val gain=10.0.pow((preset.makeupDb-reduction)/20)
        for(i in samples.indices)samples[i]=(samples[i]*gain).coerceIn(-1.0,1.0).toFloat()
    }
}
