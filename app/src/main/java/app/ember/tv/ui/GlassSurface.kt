package app.ember.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

/**
 * One background sample, tint, highlight and inside edge; foreground content stays untouched.
 * [focused]: the control's own solid focus fill lies underneath (a TV Surface paints its container
 * under its content), so only the edge is drawn and the fill shows undimmed (#39).
 *
 * The theme and composition locals are resolved here (theme colours are composable getters);
 * placement, drawing and the sample cross-fade live in a [GlassNode] (#45), so moving or a new
 * sample never rebuilds a modifier chain, and position changes only redraw.
 */
@Composable
internal fun Modifier.glassSurface(shape: Shape, panel: Boolean = false, dock: Boolean = false, focused: Boolean = false): Modifier {
    if (focused) return clip(shape).border(1.dp, Color.White.copy(alpha = .30f), shape)
    val options = LocalRenderOptions.current
    val mode = GlassPolicy.mode(options, Build.VERSION.SDK_INT, pressured = RuntimeBudgets.pressured)
    val image = LocalGlassBitmap.current.takeIf { mode != GlassMode.TINT }
    val art = LocalBackdropArtwork.current
    val viewport = art.viewport
    val day = Ui.isDay && !LocalFloating.current && !LocalNight.current
    val sampled = image != null && viewport != null
    // Theme tokens (#42): the dock stays dark over its wallpaper; OLED glass is near black.
    val base = if (panel) if (day) Panel else FloatingPanelStyle.background else if (dock) glassTint(day = false)
        else if (!sampled) glassTint(day) else PanelHi
    val alpha = if (dock) if (sampled) .20f else .40f else GlassPolicy.tintAlpha(panel, sampled, day)
    val edge = if (day) Color.Black else Color.White
    // Medium and High cross-fade between samples (#44); Low, reduced motion and no-animation profiles switch.
    val fade = mode != GlassMode.TINT && options.animationsEnabled && !options.lowPower && !LocalReduceMotion.current
    return clip(shape)
        .then(GlassElement(shape, base, alpha, if (sampled) image else null, viewport, art.focus ?: HeroFocus(), LocalGlassOrigin.current,
            mode == GlassMode.LIQUID, options.colorAnalysis, day, fade))
        .border(1.dp, Brush.linearGradient(listOf(edge.copy(alpha = if (day) .18f else .30f), edge.copy(alpha = .045f), edge.copy(alpha = .18f))), shape)
}

private data class GlassElement(
    val shape: Shape, val base: Color, val alpha: Float, val image: Bitmap?, val viewport: HeroViewport?, val focus: HeroFocus,
    val origin: Offset, val liquid: Boolean, val analysis: Boolean, val day: Boolean, val fade: Boolean,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(this)
    override fun update(node: GlassNode) = node.update(this)
    override fun InspectorInfo.inspectableProperties() { name = "glassSurface" }
}

/** How long a new glass sample takes to replace the previous one (#44). */
internal const val GLASS_CROSSFADE_MS = 250

private class GlassNode(private var style: GlassElement) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var placement = GlassPlacement()
    private var painter: GlassPainter? = style.image?.let { GlassPainter() }
    // The sample being faded out, with its own shader, and how far the new one has come in.
    private var previous: Bitmap? = null
    private var previousPainter: GlassPainter? = null
    private var progress = 1f
    private var fadeJob: kotlinx.coroutines.Job? = null
    // Outline, path and highlight for one size, shape and direction; rebuilt only when those change.
    private var cacheKey: Any? = null
    private var outline: Outline? = null
    private var path: android.graphics.Path? = null
    private var gloss: Brush? = null
    private var rounding = 0f

    fun update(next: GlassElement) {
        val old = style.image
        style = next
        if (next.image !== old) {
            fadeJob?.cancel()
            if (next.fade && old != null && next.image != null && isAttached) {
                previous = old; previousPainter = painter; painter = GlassPainter(); progress = 0f
                fadeJob = coroutineScope.launch {
                    val anim = Animatable(0f)
                    anim.animateTo(1f, tween(GLASS_CROSSFADE_MS)) { progress = value; invalidateDraw() }
                    previous = null; previousPainter = null; progress = 1f; invalidateDraw()
                }
            } else {
                previous = null; previousPainter = null; progress = 1f
                painter = next.image?.let { painter ?: GlassPainter() }
            }
        }
        cacheKey = null
        invalidateDraw()
    }

    override fun onDetach() { fadeJob?.cancel(); previous = null; previousPainter = null; progress = 1f }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (style.image == null) return
        val point = coordinates.localToScreen(Offset.Zero)
        val sx = (coordinates.localToScreen(Offset(1f, 0f)) - point).getDistance()
        val sy = (coordinates.localToScreen(Offset(0f, 1f)) - point).getDistance()
        if (point.x.isFinite() && point.y.isFinite() && sx.isFinite() && sy.isFinite() && sx > .01f && sy > .01f) {
            val next = GlassPlacement(point.x, point.y, sx, sy)
            if (next != placement) { placement = next; invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = style
        val key = Triple(size, s.shape, layoutDirection) to s.day
        if (key != cacheKey) {
            val o = s.shape.createOutline(size, layoutDirection, this)
            outline = o
            path = Path().apply { when (o) {
                is Outline.Rectangle -> addRect(o.rect)
                is Outline.Rounded -> addRoundRect(o.roundRect)
                is Outline.Generic -> addPath(o.path)
            } }.asAndroidPath()
            gloss = Brush.linearGradient(listOf(Color.White.copy(alpha = if (s.day) .10f else .13f), Color.Transparent, Color.White.copy(alpha = .025f)),
                start = Offset.Zero, end = Offset(size.width, size.height))
            rounding = if (o is Outline.Rounded) o.roundRect.topLeftCornerRadius.x else 0f
            cacheKey = key
        }
        val o = outline!!
        val viewport = s.viewport
        val visible = viewport?.let { GlassPolicy.localFrame(HeroProjection(it.left, it.top, it.width, it.height), s.origin.x, s.origin.y, placement) }
        fun frameOf(bitmap: Bitmap) = viewport?.let { HeroFraming.glassProjection(bitmap.width, bitmap.height, it, s.focus) }
            ?.let { GlassPolicy.localFrame(it, s.origin.x, s.origin.y, placement) }
        val image = s.image
        val local = image?.let(::frameOf)
        if (visible != null && path != null) {
            clipRect(visible.left, visible.top, visible.left + visible.width, visible.top + visible.height) {
                drawIntoCanvas { canvas ->
                    val old = previous; val oldPainter = previousPainter
                    if (old != null && oldPainter != null && !old.isRecycled && progress < 1f) frameOf(old)?.let { f ->
                        oldPainter.draw(canvas.nativeCanvas, path!!, old, f.left, f.top, f.width, f.height, size.width, size.height, rounding, s.liquid, density)
                    }
                    val p = painter
                    if (image != null && local != null && p != null)
                        p.draw(canvas.nativeCanvas, path!!, image, local.left, local.top, local.width, local.height, size.width, size.height, rounding, s.liquid, density,
                            alpha = if (previous != null) progress else 1f)
                }
            }
        }
        // Nine pixel reads of the small sample; never processes the hero here.
        val brightness = if (image != null && local != null && s.analysis) GlassPainter.brightness(image, local, size.width, size.height) else if (s.day) 0f else 1f
        val readable = if (image != null) GlassPolicy.readableTint(s.alpha, brightness, s.day) else s.alpha
        drawOutline(o, s.base.copy(alpha = readable))
        gloss?.let { drawOutline(o, it) }
        drawContent()
    }
}

internal class GlassPainter {
    companion object {
        fun brightness(image:Bitmap,frame:HeroProjection,width:Float,height:Float):Float {
            var result=0f
            for(y in 0..2)for(x in 0..2) {
                val px=(((x+.5f)*width/3-frame.left)*image.width/frame.width).toInt().coerceIn(0,image.width-1)
                val py=(((y+.5f)*height/3-frame.top)*image.height/frame.height).toInt().coerceIn(0,image.height-1)
                val pixel=image.getPixel(px,py)
                val light=((pixel ushr 16 and 255)*54+(pixel ushr 8 and 255)*183+(pixel and 255)*19)/(255f*256f)
                result=maxOf(result,light)
            }
            return result
        }
    }
    private var bitmap: Bitmap? = null
    private var texture: BitmapShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.06f) })
    }
    private val matrix = Matrix()
    private var lens: GlassLens? = null
    private var failed = false
    fun source(value: Bitmap?) {
        if (value === bitmap) return
        bitmap = value
        texture = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if(Build.VERSION.SDK_INT>=33)setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        } }
        lens?.source(texture)
        if (value == null) { lens = null; paint.shader = null }
    }
    fun draw(canvas: android.graphics.Canvas, path: android.graphics.Path, image: Bitmap, x: Float, y: Float,
        width: Float, height: Float, surfaceWidth: Float, surfaceHeight: Float, radius: Float, liquid: Boolean, density: Float, alpha: Float = 1f) {
        source(image)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val shader = texture ?: return
        matrix.setScale(width / image.width, height / image.height)
        matrix.postTranslate(x, y)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        if (liquid && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && !failed) {
            try {
                val current = lens ?: Api33GlassLens().also { lens = it }
                // A shader's local matrix is captured when bound as a child shader.
                current.source(shader)
                paint.shader = current.configure(surfaceWidth, surfaceHeight, radius, density)
            } catch (_: RuntimeException) { failed = true; lens = null }
        }
        try { canvas.drawPath(path, paint) }
        catch (error: RuntimeException) {
            if (paint.shader === shader) throw error
            failed = true; lens = null; paint.shader = shader; canvas.drawPath(path, paint)
        }
    }
}

internal interface GlassLens {
    fun source(shader: Shader?)
    fun configure(width: Float, height: Float, radius: Float, density: Float): Shader
}

/** API isolation keeps older Android versions on the ordinary bitmap-shader path. */
@androidx.annotation.RequiresApi(33)
internal class Api33GlassLens : GlassLens {
    private val lens = android.graphics.RuntimeShader(PROGRAM)
    override fun source(shader: Shader?) { if (shader != null) lens.setInputShader("scene", shader) }
    override fun configure(width: Float, height: Float, radius: Float, density: Float): Shader {
        lens.setFloatUniform("bounds", width, height)
        lens.setFloatUniform("rounding", radius.coerceIn(0f, minOf(width, height) / 2f))
        lens.setFloatUniform("rim", minOf(10f * density, minOf(width, height) / 3f).coerceAtLeast(.1f))
        lens.setFloatUniform("bend", 2.5f * density)
        return lens
    }
    companion object {
        // Local edge distance gives one background lookup; no dispersion or continuous time input.
        private const val PROGRAM = """
            uniform shader scene;
            uniform float2 bounds;
            uniform float rounding;
            uniform float rim;
            uniform float bend;
            half4 main(float2 p) {
                float2 centre = bounds * 0.5;
                float2 local = p - centre;
                float2 corner = abs(local) - (centre - rounding);
                float dist = length(max(corner, float2(0))) + min(max(corner.x, corner.y), 0.0) - rounding;
                float band = clamp(1.0 + dist / rim, 0.0, 1.0);
                if (band <= 0.0) return scene.eval(p);
                float2 cornerNormal = max(corner, float2(0));
                float2 edgeNormal = corner.x > corner.y ? float2(1,0) : float2(0,1);
                float2 normal = (length(cornerNormal) > 0.001 ? normalize(cornerNormal) : edgeNormal) * sign(local);
                return scene.eval(p - normal * bend * band * band);
            }
        """
    }
}
