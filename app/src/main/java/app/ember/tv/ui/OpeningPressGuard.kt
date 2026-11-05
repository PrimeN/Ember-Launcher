package app.ember.tv.ui

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** A menu must not activate its first row with the release that opened it. */
internal class OpeningPressGuard {
    private var awaitingRelease=true
    fun consume(code:Int,down:Boolean,repeat:Int):Boolean {
        if(!awaitingRelease || code !in setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER))return false
        if(!down){awaitingRelease=false;return true}
        if(repeat>0)return true
        awaitingRelease=false
        return false
    }
}
internal fun Modifier.guardOpeningPress():Modifier=composed {
    val guard=remember {OpeningPressGuard()}
    onPreviewKeyEvent {val e=it.nativeKeyEvent;guard.consume(e.keyCode,e.action==KeyEvent.ACTION_DOWN,e.repeatCount)}
}
