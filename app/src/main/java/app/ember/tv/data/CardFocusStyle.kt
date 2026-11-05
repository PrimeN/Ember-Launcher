package app.ember.tv.data

object CardFocusStyle {
    const val OFF="off"
    const val SUBTLE="subtle"
    const val LIFT="lift"
    const val GLOW="glow"
    const val PULSE="pulse"
    val choices=listOf(SUBTLE to "Subtle",LIFT to "Lift",GLOW to "Glow",PULSE to "Pulse",OFF to "Off")
    fun normalize(value:String?)=value?.takeIf {v->choices.any {it.first==v}} ?: SUBTLE
    /** Optional rich effects; inexpensive focus zoom has its own device budget. */
    fun animated(value:String,low:Boolean,reduced:Boolean)=!low && !reduced && normalize(value)!=OFF
    fun outline(requested:Boolean,value:String,low:Boolean,reduced:Boolean)=requested || !animated(value,low,reduced)
    fun pulse(value:String,low:Boolean,reduced:Boolean,focused:Boolean,active:Boolean)=
        normalize(value)==PULSE && animated(value,low,reduced) && focused && active
}
