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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * One background sample, tint, highlight and inside edge; foreground content stays untouched.
 * [focused]: the control's own solid focus fill lies underneath (a TV Surface paints its container
 * under its content), so only the edge is drawn and the fill shows undimmed (#39).
 */
internal fun Modifier.glassSurface(shape: Shape, panel: Boolean = false, dock: Boolean = false, focused: Boolean = false): Modifier = composed {
    if (focused) return@composed clip(shape).border(1.dp, Color.White.copy(alpha = .30f), shape)
    val options = LocalRenderOptions.current
    val mode = GlassPolicy.mode(options, Build.VERSION.SDK_INT, pressured = RuntimeBudgets.pressured)
    val image = LocalGlassBitmap.current.takeIf { mode != GlassMode.TINT }
    val art = LocalBackdropArtwork.current
    val viewport = art.viewport
    val focus = art.focus ?: HeroFocus()
    val origin = LocalGlassOrigin.current
    val day = Ui.isDay && !LocalFloating.current && !LocalNight.current
    // Theme tokens (#42): the dock stays dark over its wallpaper; OLED glass is near black.
    val base = if (panel) if (day) Panel else FloatingPanelStyle.background else if (dock) glassTint(day = false)
        else if (mode==GlassMode.TINT || image==null || viewport==null) glassTint(day)
        else PanelHi
    val painter = remember(image != null) { if (image != null) GlassPainter() else null }
    SideEffect { painter?.source(image) }
    var placement by remember { mutableStateOf(GlassPlacement()) }
    val sampled = image != null && viewport != null
    val alpha = if (dock) if (sampled) .20f else .40f else GlassPolicy.tintAlpha(panel, sampled, day)
    val edge = if (day) Color.Black else Color.White
    // Position and shader state belong to each attached surface, even when a modifier is reused.
    clip(shape).then(if(sampled)Modifier.onGloballyPositioned {
        val point=it.localToScreen(Offset.Zero)
        val sx=(it.localToScreen(Offset(1f,0f))-point).getDistance()
        val sy=(it.localToScreen(Offset(0f,1f))-point).getDistance()
        if(point.x.isFinite() && point.y.isFinite() && sx.isFinite() && sy.isFinite() && sx>.01f && sy>.01f)
            placement=GlassPlacement(point.x,point.y,sx,sy)
    } else Modifier)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val nativePath = if(image==null)null else Path().apply { when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            } }.asAndroidPath()
            val frame = image?.let { bitmap -> viewport?.let { HeroFraming.glassProjection(bitmap.width, bitmap.height, it, focus) } }
            val gloss = Brush.linearGradient(listOf(Color.White.copy(alpha = if (day) .10f else .13f), Color.Transparent, Color.White.copy(alpha = .025f)),
                start = Offset.Zero, end = Offset(size.width, size.height))
            val rounding = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x else 0f
            onDrawBehind {
                // Placement is read here, in the draw phase (#41): moving re-projects the sample
                // without rebuilding the cached outline and gradients on every scroll frame.
                val local=frame?.let {GlassPolicy.localFrame(it,origin.x,origin.y,placement)}
                val visible=viewport?.let {GlassPolicy.localFrame(HeroProjection(it.left,it.top,it.width,it.height),origin.x,origin.y,placement)}
                // Nine pixel reads of the small sample; never processes the hero here.
                val brightness=if(image!=null && local!=null && options.colorAnalysis) GlassPainter.brightness(image,local,size.width,size.height) else if(day)0f else 1f
                val readable=if(sampled)GlassPolicy.readableTint(alpha,brightness,day) else alpha
                if (image != null && local != null && visible != null && painter != null && nativePath != null) {
                    clipRect(visible.left,visible.top,visible.left+visible.width,visible.top+visible.height) {
                        drawIntoCanvas { canvas -> painter.draw(canvas.nativeCanvas, nativePath, image, local.left, local.top,
                            local.width, local.height, size.width, size.height, rounding, mode == GlassMode.LIQUID, density) }
                    }
                }
                drawOutline(outline, base.copy(alpha = readable))
                drawOutline(outline, gloss)
            }
        }
        .border(1.dp, Brush.linearGradient(listOf(edge.copy(alpha = if (day) .18f else .30f), edge.copy(alpha = .045f), edge.copy(alpha = .18f))), shape)
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
        width: Float, height: Float, surfaceWidth: Float, surfaceHeight: Float, radius: Float, liquid: Boolean, density: Float) {
        source(image)
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
