package app.ember.tv.screensaver

import android.service.dreams.DreamService

/** Android's automatic screensaver uses the same renderer as Screensaver now. */
class EmberDream : DreamService() {
    private var renderer: ScreensaverRenderer? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        renderer?.close()
        renderer = ScreensaverRenderer(this)
        setContentView(renderer!!.view)
    }
    override fun onDreamingStarted() { super.onDreamingStarted(); renderer?.start() }
    override fun onDreamingStopped() { renderer?.stop(); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { renderer?.close(); renderer = null; super.onDetachedFromWindow() }
    override fun onDestroy() { renderer?.close(); renderer = null; super.onDestroy() }
}
