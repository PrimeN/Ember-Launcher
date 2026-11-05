package app.ember.tv.screensaver

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Manual ambient mode uses an ordinary app-owned window, independent of OEM dream internals. */
class ScreensaverActivity : ComponentActivity() {
    private lateinit var renderer: ScreensaverRenderer
    private val dismissKeys = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
    private val exit = ScreensaverExit(dismissKeys)
    private var touchDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        renderer = ScreensaverRenderer(this)
        setContentView(renderer.view)
        onBackPressedDispatcher.addCallback(this) { finish() }
    }
    override fun onStart() { super.onStart(); renderer.start() }
    override fun onStop() {
        renderer.stop()
        if (!isChangingConfigurations) finish()
        super.onStop()
    }
    override fun onDestroy() { renderer.close(); super.onDestroy() }
    @android.annotation.SuppressLint("RestrictedApi") // Framework Activity callback/delegation, matching PlayerActivity.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in dismissKeys) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            if (exit.handle(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.isCanceled)) finish()
        }
        return true
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchDown = true
            MotionEvent.ACTION_UP -> { if (touchDown) finish(); touchDown = false }
            MotionEvent.ACTION_CANCEL -> touchDown = false
        }
        return true
    }
}
