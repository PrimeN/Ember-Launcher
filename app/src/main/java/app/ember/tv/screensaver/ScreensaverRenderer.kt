package app.ember.tv.screensaver

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.*

/** Shared native renderer for the manual activity and system-managed dream. */
class ScreensaverRenderer(private val context: android.content.Context) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private val feed = ScreensaverFeed(context)
    private var active = false
    private var generation = 0L
    private var imageJob: Job? = null
    private var displayed: android.graphics.drawable.Drawable? = null
    private val session = ScreensaverSession(scope, load = {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ScreensaverSlides.select(feed.sources) { source -> when (source) {
                ScreensaverFeed.HOME -> feed.homeSlides()
                ScreensaverFeed.ART -> feed.artSlides()
                ScreensaverFeed.SPACE -> feed.spaceSlides()
                ScreensaverFeed.PHOTOS -> Photos.slides(context, feed.photoFolder)
                else -> emptyList()
            } }.shuffled()
        }
    }, present = { present(it) })
    private lateinit var image: ImageView
    private lateinit var title: TextView
    private lateinit var credit: TextView
    val view: View = buildLayout()
    fun start() { active = true; title.text = context.getString(app.ember.tv.R.string.screensaver_loading); session.start() }
    fun stop() {
        active = false; generation++; session.stop(); imageJob?.cancel(); imageJob = null
        if (::image.isInitialized) image.setImageDrawable(null)
        displayed = null
    }
    fun close() { stop(); scope.cancel() }

    private fun present(slide: Slide?) {
        val current = ++generation
        imageJob?.cancel()
        if (slide == null) {
            image.setImageDrawable(null)
            displayed = null
            title.text = context.getString(app.ember.tv.R.string.screensaver_clock_mode)
            credit.text = context.getString(app.ember.tv.R.string.screensaver_no_pictures)
            return
        }
        imageJob = scope.launch {
            suspend fun decode(url: String) = runCatching {
                context.imageLoader.execute(ImageRequest.Builder(context).data(url)
                    .size(context.resources.displayMetrics.widthPixels.coerceIn(1280, 1920),
                        context.resources.displayMetrics.heightPixels.coerceIn(720, 1080))
                    .allowHardware(false).build())
            }.getOrNull().also { ensureActive() }
            val first = decode(slide.image)
            val fallback = slide.fallback?.takeIf { it.isNotBlank() && it != slide.image }
            val ready = if (first is SuccessResult || fallback == null) first else decode(fallback)
            ensureActive()
            if (!active || current != generation) return@launch
            val drawable = (ready as? SuccessResult)?.drawable
            if (drawable == null) {
                if (displayed == null) {
                    title.text = context.getString(app.ember.tv.R.string.screensaver_clock_mode)
                    credit.text = context.getString(app.ember.tv.R.string.screensaver_picture_unavailable)
                }
            } else {
                val previous = displayed
                displayed = drawable
                title.text = slide.title
                credit.text = slide.credit
                if (previous != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    val transition = android.graphics.drawable.TransitionDrawable(arrayOf(previous, drawable)).apply { isCrossFadeEnabled = true }
                    image.setImageDrawable(transition)
                    transition.startTransition(1200)
                } else image.setImageDrawable(drawable)
            }
        }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(context).apply { setBackgroundColor(0xFF0B0D10.toInt()) }

        image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Darken the lower left so the text stays readable on bright images.
        val shade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xE60B0D10.toInt(), 0x660B0D10, 0x000B0D10),
            )
        }
        root.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val clock = TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            setTextColor(Color.WHITE)
        }
        val weather = TextView(context).apply {
            text = runCatching { feed.weather }.getOrNull().orEmpty()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(0xFFD3D8DD.toInt())
        }
        title = TextView(context).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFFB7C0C9.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        credit = TextView(context).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF8E99A4.toInt())
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(weather)
            addView(title)
            addView(credit)
        }
        root.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(dp(64), dp(48), dp(64), dp(56)) },
        )
        return root
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
