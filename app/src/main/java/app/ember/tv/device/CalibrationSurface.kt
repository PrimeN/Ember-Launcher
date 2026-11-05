package app.ember.tv.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.ember.tv.data.CrashLog

/** Hardware-drawn SDR patterns: no bitmaps, network requests, image analysis or idle frame loop. */
@android.annotation.SuppressLint("ViewConstructor") // Created in code only, never inflated from XML.
internal class CalibrationSurface(context: Context, width: Int, height: Int, private val onSurfaceReady: () -> Unit, private val onFailure: () -> Unit) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private val paint = Paint().apply { isAntiAlias = false }
    private var ready = false
    private var active = false
    private var queued = false
    private var epoch: Long? = null
    private var reportedFailure = false
    var pattern = CalibrationPattern.SHARPNESS
        set(value) { field = value; epoch = null; draw(0); schedule() }

    init { holder.addCallback(this); holder.setFixedSize(width, height) }
    fun start() { active = true; epoch = null; draw(0); schedule() }
    fun stop() { active = false; Choreographer.getInstance().removeFrameCallback(this); queued = false }
    override fun surfaceCreated(holder: SurfaceHolder) { ready = true; draw(0); schedule() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { draw(0); post { onSurfaceReady() } }
    override fun surfaceDestroyed(holder: SurfaceHolder) { ready = false; Choreographer.getInstance().removeFrameCallback(this); queued = false }
    private fun schedule() {
        if (active && ready && pattern.fps > 0 && !queued) {
            queued = true; Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun doFrame(frameTimeNanos: Long) {
        queued = false
        if (!active || !ready || pattern.fps == 0) return
        val start = epoch ?: frameTimeNanos.also { epoch = it }
        draw(frameTimeNanos - start)
        schedule()
    }
    private fun draw(elapsed: Long) {
        if (!ready || !holder.surface.isValid) return
        try {
            val canvas = holder.lockHardwareCanvas()
            try { render(canvas, elapsed) } finally { holder.unlockCanvasAndPost(canvas) }
        } catch (e: RuntimeException) {
            if (!reportedFailure) { reportedFailure = true; stop(); CrashLog.note("Could not draw calibration surface", e); onFailure() }
        }
    }
    private fun rect(c: Canvas, x: Float, y: Float, right: Float, bottom: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL; paint.shader = null
        c.drawRect(x, y, right, bottom, paint)
    }
    private fun render(c: Canvas, elapsed: Long) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        c.drawColor(Color.BLACK)
        when (pattern) {
            CalibrationPattern.SHARPNESS -> {
                // Pixel-accurate alternating vertical and horizontal strips alongside a coarse grid.
                val mid = w.toInt() / 2
                for (x in 0 until mid step 2) rect(c, x.toFloat(), 0f, x + 1f, h / 2, Color.WHITE)
                for (y in 0 until (h / 2).toInt() step 2) rect(c, mid.toFloat(), y.toFloat(), w, y + 1f, Color.WHITE)
                paint.color = Color.WHITE; paint.strokeWidth = 1f
                for (i in 0..32) { val x = w * i / 32; c.drawLine(x, h / 2, x, h, paint) }
                for (i in 0..9) { val y = h / 2 + h * i / 18; c.drawLine(0f, y, w, y, paint) }
            }
            CalibrationPattern.OVERSCAN -> {
                rect(c, 0f, 0f, w, h, Color.DKGRAY)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                for (percent in listOf(0f, .025f, .05f)) {
                    paint.color = if (percent == 0f) Color.WHITE else if (percent == .025f) Color.GREEN else Color.CYAN
                    c.drawRect(w * percent + 1, h * percent + 1, w * (1-percent) - 1, h * (1-percent) - 1, paint)
                }
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE
                c.drawCircle(w / 2, h / 2, h * .35f, paint)
                c.drawLine(0f, h / 2, w, h / 2, paint); c.drawLine(w / 2, 0f, w / 2, h, paint)
                paint.style = Paint.Style.FILL
            }
            CalibrationPattern.BLACK, CalibrationPattern.WHITE -> {
                val black = pattern == CalibrationPattern.BLACK
                c.drawColor(if (black) Color.BLACK else Color.WHITE)
                val levels = if (black) intArrayOf(0, 1, 2, 3, 4, 6, 8, 12, 16, 24) else intArrayOf(231, 239, 243, 247, 249, 251, 252, 253, 254, 255)
                levels.forEachIndexed { index, level ->
                    rect(c, w * index / levels.size, h * .25f, w * (index + 1) / levels.size, h * .75f, Color.rgb(level, level, level))
                    paint.color = if (black) Color.GRAY else Color.DKGRAY; paint.textSize = h / 36
                    c.drawText(level.toString(), w * index / levels.size + w * .015f, h * .85f, paint)
                }
            }
            CalibrationPattern.GRAYSCALE -> {
                for (i in 0..20) { val level = i * 255 / 20; rect(c, w * i / 21, 0f, w * (i+1) / 21, h / 2, Color.rgb(level, level, level)) }
                paint.shader = LinearGradient(0f, 0f, w, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
                c.drawRect(0f, h / 2, w, h, paint); paint.shader = null
            }
            CalibrationPattern.COLORS -> {
                val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK)
                colors.forEachIndexed { i, color -> rect(c, w * i / colors.size, 0f, w * (i+1) / colors.size, h, color) }
            }
            CalibrationPattern.JUDDER24, CalibrationPattern.JUDDER60 -> {
                val x = CalibrationMath.position(elapsed, pattern.fps) * w
                rect(c, x, h * .15f, x + w * .025f, h * .85f, Color.WHITE)
                paint.color = Color.GRAY; paint.strokeWidth = 2f
                for (i in 0..8) c.drawLine(w * i / 8, h * .9f, w * i / 8, h, paint)
            }
        }
    }
}
