package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Profile

/** A child gets one settings visit after PIN approval; never persist authorization in a backup. */
internal class SettingsAccess(
    private val profile:()->Profile,
    private val pinHash:()->String?,
    private val requestPin:(String,()->Unit)->Unit,
    private val notice:(String)->Unit,
) {
    private data class Stamp(val profile:Profile,val hash:String)
    private var grant by mutableStateOf<Stamp?>(null)
    private var generation=0L
    private fun stamp():Stamp?=pinHash()?.takeIf {it.isNotBlank()}?.let {Stamp(profile(),it)}
    val allowed:Boolean get()=!profile().kids || stamp()?.let {it==grant}==true
    fun revoke() {grant=null;generation++}
    fun open(action:()->Unit) {
        if(!profile().kids) {action();return}
        val expected=stamp()
        if(expected==null) {
            notice("Settings are locked for Kids. Switch to an adult profile to set the administrator PIN.")
            return
        }
        if(allowed) {action();return}
        val request=++generation
        requestPin("Enter the administrator PIN to open Settings") {
            if(request==generation && stamp()==expected) {grant=expected;action()}
        }
    }
}
