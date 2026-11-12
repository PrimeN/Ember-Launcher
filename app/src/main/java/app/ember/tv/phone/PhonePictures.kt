package app.ember.tv.phone

import androidx.compose.ui.graphics.toArgb

/**
 * #232: the TV's setting pictures (#203, #208) for the phone page, drawn as small inline SVGs, so a
 * choice is a picture of itself there too. Pictures use only shapes and the page's accent colour;
 * they load nothing.
 */
internal object PhonePictures {
    private const val W = "#e8eaed"
    private const val DIM = "rgba(255,255,255,.38)"
    private const val CARD = "#5b5d63"
    private const val SCREEN = "#2a2c33"
    private const val ACCENT = "var(--accent)"

    /** Settings drawn as pictures; others keep pills or a list. */
    val pictured = setOf("look.interface", "look.navbar", "look.mediaPageLayout", "look.theme", "look.accent", "look.size", "look.text",
        "look.density", "look.menu", "look.appIcons", "look.cards", "look.wallpaper", "play.interface")

    fun has(id: String) = id in pictured

    /** Numeric choices in rising order (seconds, sizes, margins) slide like the TV's step sliders (#209). */
    fun stepped(options: List<Pair<String, String>>): Boolean = options.size >= 4 &&
        options.map { it.first.toDoubleOrNull() }.let { n -> n.all { it != null } && n.zipWithNext().all { (a, b) -> a!! < b!! } }

    /** A few short choices show as buttons rather than a list. */
    fun pills(options: List<Pair<String, String>>): Boolean = options.size in 2..5 && options.all { it.second.length <= 18 }

    /** A number with a short range slides; a long one stays a typed number. */
    fun slides(min: Int, max: Int): Boolean = max > min && max - min <= 500

    /** The picture of [value] for setting [id], or null when it has none. */
    fun of(id: String, value: String): String? = when (id) {
        "look.interface" -> if (value == app.ember.tv.data.InterfaceStyles.LIGHT) light("art", "if") else home()
        "look.navbar" -> navbar(value)
        "look.mediaPageLayout" -> mediaPage(value)
        "look.theme" -> theme(value)
        "look.accent" -> accent(value.toIntOrNull()?.let { app.ember.tv.ui.Accents.getOrNull(it)?.second }?.let(::hex) ?: CARD)
        "look.size" -> size(value, text = false)
        "look.text" -> size(value, text = true)
        "look.density" -> density(value)
        "look.menu" -> menu(value)
        "look.appIcons" -> appIcons(value)
        "look.cards" -> cards(value)
        "look.wallpaper" -> light(value, "wp")
        "play.interface" -> player(value)
        else -> null
    }

    internal fun hex(color: androidx.compose.ui.graphics.Color): String = String.format(java.util.Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)

    // ---- drawing
    private fun svg(body: String, bg: String = SCREEN, defs: String = "") =
        "<svg viewBox=\"0 0 80 50\" aria-hidden=\"true\" focusable=\"false\">$defs<rect width=\"80\" height=\"50\" rx=\"5\" style=\"fill:$bg\"/>$body</svg>"
    private fun r(x: Any, y: Any, w: Any, h: Any, fill: String, rx: Any = 1.5) =
        "<rect x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\" rx=\"$rx\" style=\"fill:$fill\"/>"
    private fun c(cx: Any, cy: Any, radius: Any, fill: String) = "<circle cx=\"$cx\" cy=\"$cy\" r=\"$radius\" style=\"fill:$fill\"/>"
    private fun gradient(id: String, colors: List<String>) = "<defs><linearGradient id=\"$id\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">" +
        colors.mapIndexed { i, col -> "<stop offset=\"${if (colors.size == 1) 0 else i * 100 / (colors.size - 1)}%\" stop-color=\"$col\"/>" }.joinToString("") + "</linearGradient></defs>"
    private fun row(y: Any, count: Int, w: Double, h: Double, gap: Double = 3.0, x: Double = 6.0, fill: String = CARD, first: String = fill) =
        (0 until count).joinToString("") { i -> r(fmt(x + i * (w + gap)), y, fmt(w), fmt(h), if (i == 0) first else fill) }
    private fun fmt(d: Double) = if (d == kotlin.math.floor(d)) d.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", d)
    private fun pills(x: Int, y: Int, n: Int, first: String = ACCENT) = (0 until n).joinToString("") { i -> r(x + i * 11, y, 9, 4, if (i == 0) first else DIM, 2) }

    /** Ember Home: menu, a hero and a row of cards. */
    private fun home() = svg(pills(6, 5, 4) + r(6, 13, 34, 4, W) + r(6, 20, 22, 3, DIM) + row(31, 5, 12.0, 14.0, first = ACCENT))

    /** Ember Light and its wallpapers: the real gradient with a grid of app tiles. */
    private fun light(wallpaper: String, prefix: String): String {
        val colors = app.ember.tv.ui.lightWallpaperColors(wallpaper).map(::hex)
        val id = "$prefix-${wallpaper.filter { it.isLetterOrDigit() }}"
        val art = if (wallpaper == "art") r(46, 6, 26, 15, "rgba(255,255,255,.16)", 2) else ""
        val tiles = (0..1).joinToString("") { line -> row(26 + line * 10, 6, 9.5, 7.0, 2.2, 6.0, "rgba(255,255,255,.3)", if (line == 0) W else "rgba(255,255,255,.3)") }
        return svg(art + r(6, 7, 20, 3, W) + r(6, 12, 13, 2, DIM) + tiles, "url(#$id)", gradient(id, colors))
    }

    private fun navbar(style: String) = when (style) {
        app.ember.tv.data.NavbarStyles.COLLAPSED -> svg(c(9, 7, 3, ACCENT) + r(6, 16, 30, 4, W) + r(6, 23, 20, 3, DIM) + row(33, 5, 12.0, 12.0))
        app.ember.tv.data.NavbarStyles.CINEMA -> svg(r(0, 0, 80, 10, "#0b0b0d", 0) + r(6, 3.5, 8, 3, ACCENT, 1) + r(18, 3.5, 8, 3, DIM, 1) + r(30, 3.5, 8, 3, DIM, 1) + r(6, 16, 30, 4, W) + row(33, 5, 12.0, 12.0))
        else -> svg(pills(22, 5, 4) + r(6, 16, 30, 4, W) + r(6, 23, 20, 3, DIM) + row(33, 5, 12.0, 12.0))
    }

    private fun mediaPage(style: String) = if (style == app.ember.tv.data.MediaPageLayouts.EMBER_X)
        svg(r(0, 0, 50, 50, "#3a4150", 0) + r(50, 0, 30, 50, "#16181d", 0) + r(54, 8, 20, 4, W) + r(54, 15, 14, 2, DIM) + r(54, 20, 18, 2, DIM) + r(54, 30, 22, 5, ACCENT, 2) + r(54, 38, 22, 5, DIM, 2))
    else svg(r(0, 0, 80, 50, "#3a4150", 5) + r(6, 10, 30, 5, W) + r(6, 18, 40, 2, DIM) + r(6, 22, 34, 2, DIM) + r(6, 31, 18, 6, ACCENT, 3) + r(27, 31, 12, 6, DIM, 3) + r(42, 31, 12, 6, DIM, 3))

    private fun theme(value: String): String {
        val night = svg(r(6, 6, 30, 4, W) + row(18, 5, 12.0, 14.0, first = ACCENT) + r(6, 38, 50, 3, DIM), "#141518")
        val day = svg(r(6, 6, 30, 4, "#1f2125") + row(18, 5, 12.0, 14.0, fill = "#c7cbd1", first = ACCENT) + r(6, 38, 50, 3, "#9aa0a8"), "#eef0f3")
        return when (value) {
            "day" -> day
            "auto" -> svg("<path d=\"M80 0 V50 H0 Z\" style=\"fill:#eef0f3\"/>" + c(20, 16, 6, W) + c(62, 34, 6, "#f5b942"), "#141518")
            else -> night
        }
    }

    /** A colour theme in its own colour: the menu pill and a focused card. */
    private fun accent(color: String) = svg(r(6, 5, 9, 4, color, 2) + r(17, 5, 9, 4, DIM, 2) + r(28, 5, 9, 4, DIM, 2) +
        "<rect x=\"5\" y=\"17\" width=\"16\" height=\"20\" rx=\"2.5\" style=\"fill:$CARD;stroke:$color;stroke-width:2\"/>" + row(18, 3, 13.0, 18.0, 4.0, 25.0) + r(6, 42, 68, 3, color, 1.5))

    private fun size(value: String, text: Boolean): String {
        val step = app.ember.tv.ui.UiSizes.LABELS.indexOfFirst { it.first == value }.coerceAtLeast(0)
        return if (text) svg("<text x=\"40\" y=\"33\" text-anchor=\"middle\" style=\"fill:$W;font:600 ${12 + step * 4}px system-ui,sans-serif\">Aa</text>")
        else {
            val w = 9.0 + step * 2.5
            val count = (68 / (w + 3)).toInt().coerceIn(2, 6)
            svg(r(6, 6, 14 + step * 4, fmt(3 + step * .6), W) + row(18, count, w, w * 1.3, first = ACCENT))
        }
    }

    private fun density(value: String): String {
        val n = when (value) { "comfortable" -> 4; "dense" -> 7; else -> 5 }
        val w = (68.0 - (n - 1) * 3) / n
        return svg(r(6, 6, 24, 3, W) + row(14, n, w, w * 1.45, first = ACCENT))
    }

    private fun menu(value: String) = if (value == "side")
        svg(r(0, 0, 14, 50, "#1b1c20", 0) + (0..3).joinToString("") { i -> r(4, 8 + i * 9, 6, 5, if (i == 0) ACCENT else DIM, 1.5) } + r(20, 8, 26, 4, W) + row(22, 4, 12.0, 15.0, x = 20.0))
    else svg(pills(6, 5, 4) + r(6, 14, 30, 4, W) + row(26, 5, 12.0, 15.0))

    private fun appIcons(shape: String) = svg(when (shape) {
        "circle" -> (0..3).joinToString("") { i -> c(13 + i * 18, 25, 7, if (i == 0) ACCENT else CARD) }
        "square" -> (0..3).joinToString("") { i -> r(6 + i * 18, 18, 14, 14, if (i == 0) ACCENT else CARD, 3) }
        else -> row(19, 3, 20.0, 12.0, 4.0, 6.0, first = ACCENT)
    })

    private fun cards(shape: String) = svg(r(6, 6, 24, 3, W) + if (shape == "poster") row(15, 5, 11.5, 17.0, first = ACCENT) else row(17, 3, 20.0, 11.5, 4.0, first = ACCENT))

    /** As the TV's player pictures: Ember's dark bar, Glass's frosted bar, Basic's centred controls, Streaming's corners. */
    private fun player(style: String): String {
        val bg = "url(#pl-$style)"
        val defs = gradient("pl-$style", listOf("#3A4150", "#151820")).replace("x2=\"1\" y2=\"1\"", "x2=\"0\" y2=\"1\"")
        val body = when (style) {
            app.ember.tv.player.PlayerInterfaces.STREAMING -> c(9, 8, 2.5, W) + c(16, 8, 2.5, DIM) + c(23, 8, 2.5, DIM) + r(44, 6, 30, 4, W) + r(54, 12, 20, 3, DIM) +
                r(6, 33, 28, 6, W) + c(9, 44, 3, W) + r(15, 43, 59, 2, DIM, 1)
            app.ember.tv.player.PlayerInterfaces.EMBER_X -> r(6, 33, 68, 2, DIM, 1) + r(6, 33, 27, 2, W, 1) + c(31, 42, 3, DIM) + c(40, 42, 5, W) + c(49, 42, 3, DIM)
            else -> {
                val glass = style == app.ember.tv.player.PlayerInterfaces.MODERN
                r(4, 24, 72, 22, if (glass) "rgba(255,255,255,.22)" else "rgba(16,16,18,.9)", if (glass) 5 else 2) + r(8, 28, 30, 4, W) + r(8, 35, 64, 2, DIM, 1) + r(8, 35, 36, 2, W, 1) +
                    (0..5).joinToString("") { i -> c(10 + i * 7, 41.5, 2.2, if (i == 1) W else DIM) }
            }
        }
        return svg(body, bg, defs)
    }
}
