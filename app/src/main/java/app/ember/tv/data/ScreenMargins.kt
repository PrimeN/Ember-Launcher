package app.ember.tv.data

/** Restored numeric strings must never become NaN layout dimensions or hide all controls. */
internal object ScreenMargins {
    fun valid(value:String):Boolean=value.toFloatOrNull()?.let {it.isFinite() && it in 0f..5f}==true
    fun normalize(value:String?):String {
        val number=value?.toFloatOrNull()?.takeIf {it.isFinite()}?.coerceIn(0f,5f) ?: 0f
        return if(number==number.toInt().toFloat())number.toInt().toString() else number.toString()
    }
    fun fraction(value:String)=normalize(value).toFloat()/100f
}
