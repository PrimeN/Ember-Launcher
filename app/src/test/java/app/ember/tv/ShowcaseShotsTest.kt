package app.ember.tv

import android.app.Application
import app.ember.tv.data.CatalogRow
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.ember.tv.data.*
import app.ember.tv.iptv.Channel
import app.ember.tv.iptv.Programme
import app.ember.tv.player.AdvancedControls
import app.ember.tv.player.ControlsActions
import app.ember.tv.player.ControlsState
import app.ember.tv.player.PlayerDetails
import app.ember.tv.ui.*
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Showcase pictures of Ember's real screens, filled with invented films, shows, channels and apps
 * whose artwork is drawn here. They go to the private screenshots repo with the other UI shots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShowcaseShotsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var loader: ImageLoader

    @Before fun images() {
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<android.net.Uri> {
                override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
                    if (data.scheme != "fixture") null else object : Fetcher {
                        override suspend fun fetch(): FetchResult =
                            DrawableResult(BitmapDrawable(context.resources, Art.draw(data.host.orEmpty(), data.lastPathSegment.orEmpty())), false, DataSource.NETWORK)
                    }
            })
        }.build()
        Coil.setImageLoader(loader)
    }
    @After fun clean() { loader.shutdown(); Coil.reset(); Ui.day = false }

    // ---- invented titles ----
    private val films = listOf(
        "Northern Drift" to "2024", "The Glass Orchard" to "2023", "Lanterns of Vey" to "2025", "Signal Lost" to "2022",
        "Hollow Tide" to "2024", "Kestrel Run" to "2021", "Paper Comets" to "2025", "The Quiet Hours" to "2023",
        "Salt and Ember" to "2024", "Orbitals" to "2026", "Velvet Static" to "2022", "Cold Harbor" to "2025",
        "Wildflower Season" to "2024", "Midnight Cartographers" to "2023", "The Long Meridian" to "2026", "Copper Sky" to "2021",
    )
    private fun art(kind: String, title: String) = "fixture://$kind/${android.net.Uri.encode(title)}"
    private fun meta(title: String, year: String = "2025", type: String = "movie", badge: String? = null, progress: Float? = null) = Meta(
        id = "show:" + title.lowercase().replace(' ', '-'), type = type, name = title, releaseInfo = year,
        poster = art("poster", title), background = art("backdrop", title), logo = art("logo", title),
        description = "A crew of strangers follows a fading radio signal across a frozen sea, and finds a city that should not exist.",
        runtime = "2h 12m", genres = listOf("Sci-Fi", "Adventure"), badge = badge, progress = progress, ageRating = "PG-13")
    private val posters get() = films.map { (t, y) -> meta(t, y) }
    private val shows = listOf("The Lighthouse Keepers", "Brightwater", "Station Nine", "Hearthside", "The Overnight", "Iron Valley")

    private fun save(name: String, settle: Long = 0) {
        if (settle > 0) { Thread.sleep(settle); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); Thread.sleep(settle) }
        // Artwork is drawn off the main thread: wait until two captures agree.
        var last: Bitmap? = null
        repeat(14) {
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            Thread.sleep(150)
            val shot = compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap()
            if (last?.sameAs(shot) == true) { write(name, shot); return }
            last = shot
        }
        last?.let { write(name, it) }
    }
    private fun write(name: String, bitmap: Bitmap) {
        val file = java.io.File("build/reports/ui/showcase-$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun Frame(night: Boolean = true, launcher: String = InterfaceStyles.MODERN, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNight provides night, LocalReduceMotion provides true, LocalFixedLauncherTime provides "8:30 PM",
            LocalRenderOptions provides RenderOptions(quality = "high")) {
            EmberTheme(0, launcher) {
                CompositionLocalProvider(LocalContentColor provides Fg) {
                    Box(Modifier.size(960.dp, 540.dp).background(if (night) Color(0xFF101012) else Color(0xFFF4F1EC)).testTag("scene")) { content() }
                }
            }
        }
    }

    @Composable private fun HomeScene(style: String) {
        val nav = remember { NavigationFocus() }
        val heroes = posters.take(5)
        CompositionLocalProvider(LocalNavigationFocus provides nav) {
            ModernBackdrop(heroes.first(), dim = 0f, heightFraction = HeroBanner.BACKDROP_FRACTION, shade = .82f)
            LauncherNavbarFrame(style, InterfaceStyles.MODERN, "top", listOf(NavTabs.SEARCH, NavTabs.HOME, NavTabs.DISCOVER, NavTabs.LIVE, NavTabs.APPS, NavTabs.LIBRARY),
                NavTabs.HOME, "18°", Profile(id = "main", name = "Alex"), onTab = {}, onProfile = {}) { _ ->
                Column(Modifier.fillMaxSize()) {
                    ModernHero(heroes, 0, {}, 290.dp, { false }, {}, {}, {}, rotating = false)
                    Column(Modifier.padding(horizontal = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ModernSection("Continue watching") {
                            MediaRow(shows.mapIndexed { i, t -> meta(t, type = "series", badge = "S${i + 1} · E${i + 3}", progress = .2f + i * .12f) }, true, { false }) {}
                        }
                        ModernSection("Trending this week", "Cinemeta") { MediaRow(posters.drop(5), false, { false }) {} }
                    }
                }
            }
        }
    }

    @Test fun home() { compose.setContent { Frame { HomeScene(NavbarStyles.EMBER) } }; save("home") }
    @Test fun homeDay() { Ui.day = true; compose.setContent { Frame(night = false) { HomeScene(NavbarStyles.CINEMA) } }; save("home-day") }

    @Test fun titlePage() {
        val title = meta("Lanterns of Vey", "2025")
        val streams = listOf(
            StreamOption("Torrentio", "4K", "Lanterns of Vey 2025 2160p DV HDR10 TrueHD Atmos 7.1 REMUX", "https://example.invalid/1", null, null, "4K", "DV HDR10", "HEVC", "TrueHD Atmos 7.1", "REMUX", "FraMeSToR", 64.2, null, true, true),
            StreamOption("Comet", "4K", "Lanterns of Vey 2160p HDR10 DDP Atmos 5.1 WEB-DL", "https://example.invalid/2", null, null, "4K", "HDR10", "HEVC", "DD+ Atmos 5.1", "WEB-DL", "FLUX", 18.6, null, true, true),
            StreamOption("Your Plex", "1080p", "Lanterns of Vey (2025)", "https://example.invalid/3", null, null, "FHD", "", "H.264", "DTS-HD MA 5.1", "BluRay", "", 22.1, null, true),
        )
        val people = listOf("Mara Ellison" to "Director", "Teo Varga" to "Ilsa", "June Okafor" to "Pell", "Rafe Holloway" to "Captain Dorn", "Ines Calder" to "The Keeper", "Oren Blake" to "Nils")
            .mapIndexed { i, (n, r) -> Person(n, r, art("person", n), isCrew = i == 0) }
        val d = DetailsUi(title, streams = streams, addonCredits = Credits(people, "trailer"),
            related = RelatedTitles("Vey Collection", listOf(meta("Lanterns of Vey", "2025"), meta("Embers of Vey", "2027")), posters.take(8)),
            parentsGuide = listOf(app.ember.tv.tracker.ParentsGuide.Entry("Violence", "Moderate"), app.ember.tv.tracker.ParentsGuide.Entry("Profanity", "Mild")),
            ratings = mapOf("imdb" to "8.1", "tmdb" to "7.9", "rt" to "94%"))
        compose.setContent { Frame { MediaPageLayoutScope(MediaPageLayouts.EMBER_X) {
            ModernBackdrop(title, dim = .35f)
            DetailsScreen(d = d, listState = rememberLazyListState(), saved = true, isContinue = false, onToggleSaved = {}, onRemoveContinue = {},
                onSeason = {}, onEpisode = {}, onMoreEpisodes = {}, onPlay = {}, onPlayWith = {}, playButton = true, best = streams.first(),
                playbackLabel = "Play", onPlayBest = {})
        } } }
        save("title")
    }

    @Test fun showPage() {
        val title = meta("The Lighthouse Keepers", "2024", type = "series")
        val episodes = (1..10).map { Episode("e$it", 2, it, listOf("The Lamp Room", "Fog Season", "Tidewater", "Night Shift", "Breakers", "The Long Watch", "Gulls", "Low Light", "Ironside", "Beacon")[it - 1],
            "2024-0${(it % 9) + 1}-12", art("still", "Keepers $it"), progress = if (it == 3) .45f else null, overview = "A storm cuts the island off just as the keepers find a message in the lamp.") }
        val d = DetailsUi(title, episodes = episodes, season = 2, episode = episodes[2], streams = listOf(
            StreamOption("Comet", "4K", "S02E03 2160p DV DDP 5.1", "https://example.invalid/e", null, null, "4K", "DV", "HEVC", "DD+ 5.1", "WEB-DL", "", 6.2, null, true, true)))
        compose.setContent { Frame { MediaPageLayoutScope(MediaPageLayouts.MODERN) {
            ModernBackdrop(title, dim = .35f)
            DetailsScreen(d = d, listState = rememberLazyListState(), saved = false, isContinue = true, onToggleSaved = {}, onRemoveContinue = {},
                onSeason = {}, onEpisode = {}, onMoreEpisodes = {}, onPlay = {}, onPlayWith = {}, playButton = true, best = d.streams.first(), playbackLabel = "Resume 21:40")
        } } }
        save("show")
    }

    @Test fun player() {
        val s = ControlsState().apply {
            visible = true; title = "Northern Drift"; seasonEpisode = null
            details = PlayerDetails("Northern Drift", logo = art("logo", "Northern Drift"), year = "2024", rating = "IMDb 7.8",
                synopsis = "A crew of strangers follows a fading radio signal across a frozen sea.",
                cast = listOf("Teo Varga", "June Okafor", "Rafe Holloway").map { Person(it, null, art("person", it)) }, backdrop = art("backdrop", "Northern Drift"))
            position = 52 * 60_000L + 7_000; duration = 128 * 60_000L; buffered = 70 * 60_000L
            audioTracks = listOf("English · TrueHD Atmos 7.1" to true, "French · DD+ 5.1" to false)
            subtitleOptions = listOf("Off" to false, "English" to true)
        }
        compose.setContent { Frame {
            coil.compose.AsyncImage(art("backdrop", "Northern Drift"), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            AdvancedControls(s, ControlsActions(playPause = {}, seekBy = {}, skip = {}, openMenu = { _, _ -> }, options = { emptyList() }, next = {}, playEpisode = {},
                touched = {}, pickAudio = {}, pickSubtitle = {}))
        } }
        save("player")
    }

    private val channelNames = listOf("Northline News", "Fieldhouse Sports", "Cinemora", "Wavelet Music", "Puddle Kids", "Skyline Weather", "Reelhouse Classics", "Arcadia Games",
        "Harbor 24", "Metro One", "Lumen Docs", "Gourmet Lane", "Velocity Motors", "Atlas Travel", "Comedy Corner", "Nightjar Drama")
    private val clock = 90_900_000L
    private val channels = channelNames.mapIndexed { i, n -> Channel("a:$i", "a", n, "https://example.test/$i", logo = art("channel", n), group = "Entertainment", number = 101 + i) }
    private val showsOnAir = listOf("Evening Report", "Match of the Night", "The Glass Orchard", "Late Grooves", "Puddle Pals", "Storm Watch", "Kestrel Run", "Pixel Cup",
        "Harbor Tonight", "City Beat", "Deep Blue Planet", "Kitchen Heat", "Track Day", "Island Hopping", "Stand-up Hour", "The Quiet Hours")
    private fun programmes(c: Channel): List<Programme> {
        val i = channels.indexOf(c).coerceAtLeast(0)
        return listOf(Programme(clock - 900_000 - i * 60_000, clock + 1_500_000 + i * 90_000, showsOnAir[i], description = "Live from the studio with the day's biggest stories."),
            Programme(clock + 1_500_000 + i * 90_000, clock + 4_200_000, "Coming up: " + showsOnAir[(i + 3) % showsOnAir.size]))
    }

    @Test fun liveOnNow() {
        compose.setContent { Frame { LivePageTheme {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                EmberLogo(26.dp, Fg)
                LiveViewToolbar("Home playlist", "All channels", true, false, true, remember { androidx.compose.ui.focus.FocusRequester() }, remember { androidx.compose.ui.focus.FocusRequester() },
                    remember { androidx.compose.ui.focus.FocusRequester() }, remember { androidx.compose.ui.focus.FocusRequester() }, onSource = {}, onView = {}, onScope = {}, onSidebar = {}, onSearch = {}, onNow = {}, onMore = {})
                LiveOnNowCanvas(channels, { programmes(it).firstOrNull() }, clock, mapOf("a" to "Home playlist"), onFocus = {}, onOptions = {}, onPlay = {}, modifier = Modifier.weight(1f))
            }
        } } }
        save("live-on-now")
    }

    @Test fun liveGuide() {
        compose.setContent { Frame { LivePageTheme {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                EmberLogo(26.dp, Fg)
                LiveProgrammePanel(channels[2], programmes(channels[2]).first(), clock, "Home playlist", "", compact = true, favourite = true, onWatch = {}, onFavourite = {})
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GuideGrid(channels, ::programmes, setOf("a:2"), clock, onPlay = {}, onFavourite = {}, showControls = false, showPanel = false, onSelection = { _, _, _ -> }, onProgramme = { _, _ -> })
                }
            }
        } } }
        save("live-guide")
    }

    @Test fun liveOverlay() {
        val g = LiveGuideState(channels, 4, "a:2", clock)
        compose.setContent { Frame {
            coil.compose.AsyncImage(art("backdrop", "Match of the Night"), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            LiveGuideOverlay(g, { c, _ -> programmes(c).let { it[0] to it[1] } }) { "Home playlist" }
        } }
        save("live-overlay")
    }

    @Test fun search() {
        val q = "cosy mystery in a small english village"
        val answer = listOf("The Quiet Hours", "Hollow Tide", "Wildflower Season", "Cold Harbor", "Copper Sky", "Paper Comets", "Signal Lost").map {
            meta(it, badge = listOf("A village with secrets, gently told", "Rain, tea and a missing vicar", "Small-town puzzles, no gore")[it.length % 3]) }
        val state = SearchCanvasState(profileId = "main", query = q, rows = listOf(CatalogRow("Cinemeta", "Movies", posters.take(6))),
            ai = SearchAi("Google Gemini", q, answer, false, null))
        compose.setContent { Frame { SearchCanvas(state, SearchCanvasActions({}, {}, {}, {}, {}, {}, {}, {}, {})) } }
        save("search")
    }

    @Test fun spark() {
        fun row(title: String, sub: String?, names: List<String>, why: (String) -> String) = title to Pair(sub, names.map { meta(it, badge = why(it)) })
        val rows = listOf(
            row("Top picks for you", "From what you've been watching", listOf("Orbitals", "Signal Lost", "The Long Meridian", "Velvet Static", "Kestrel Run", "Copper Sky", "Paper Comets")) { "Because you watched Northern Drift · ★ 8.1" },
            row("Mind-bending sci-fi", null, listOf("Midnight Cartographers", "Lanterns of Vey", "Northern Drift", "The Glass Orchard", "Hollow Tide", "Cold Harbor", "Salt and Ember")) { "You like sci-fi · ★ 7.6" },
        )
        compose.setContent { Frame {
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Spark", fontSize = 26.sp)
                Text("Spark has noticed you like Sci-fi · Drama · Thriller · 2020s · Korean · picked Tue 24 Nov, 8:12 PM", color = Mute, fontSize = 13.sp)
                rows.forEach { (title, v) -> ModernSection(title, v.first) { MediaRow(v.second, false, { false }) {} } }
            }
        } }
        save("spark")
    }

    @Test fun apps() {
        val names = listOf("Cinemora", "Lumen TV", "Wavelet Music", "Arcadia Games", "Northline News", "Fieldhouse Sports", "Puddle Kids", "Reelhouse", "Podhaven", "Frameworth Photos",
            "Skyline Weather", "Kinetic Fit", "Ember Player", "Atlas Travel", "Gourmet Lane", "Harbor Radio", "Paperback Audio", "Studio Live")
        val apps = names.mapIndexed { i, n -> AppEntry("app.$i", n, Intent(), BitmapDrawable(context.resources, Art.draw("banner", n))) }
        compose.setContent { Frame {
            Column(Modifier.fillMaxSize()) { AppLibraryCanvas(apps, emptySet(), "main", onLaunch = {}, onOptions = {}, onRefresh = {}, modifier = Modifier.weight(1f)) }
        } }
        save("apps", settle = 2_500)
    }

    /** #445: Ember Light's Home, from a real Home model given invented apps and titles. */
    @Test fun lightHome() {
        val vm = HomeViewModel(context)
        val names = listOf("Cinemora", "Lumen TV", "Reelhouse", "Wavelet Music", "Fieldhouse Sports", "Arcadia Games", "Northline News", "Puddle Kids")
        fun fill() {
            vm.apps = names.mapIndexed { i, n -> AppEntry("app.showcase.$i", n, Intent(), BitmapDrawable(context.resources, Art.draw("banner", n))) }
            vm.continueItems = shows.take(3).mapIndexed { i, t -> meta(t, type = "series", badge = "S${i + 1} · E${i + 3}", progress = .3f + i * .2f) }
            vm.catalogs.rows = listOf(CatalogRow("Cinemeta", "Popular", posters.take(8)))
        }
        fill()
        compose.setContent {
            CompositionLocalProvider(app.ember.tv.ui.LocalInternetStatus provides true) {
                Frame(launcher = InterfaceStyles.LIGHT) {
                    app.ember.tv.ui.LightHome(vm, androidx.compose.foundation.lazy.rememberLazyListState(), listOf(NavTabs.HOME, NavTabs.SEARCH, NavTabs.LIVE, NavTabs.APPS),
                        onTab = {}, onOpenTitle = {}, onLaunch = {}, onAppOptions = {})
                }
            }
        }
        compose.waitForIdle(); fill()
        save("light-home", settle = 2_500)
    }

    /** #445: the recovery page Ember opens after three failed starts. */
    @Test fun recovery() {
        val activity = org.robolectric.Robolectric.buildActivity(app.ember.tv.system.RecoveryActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        val w = 1920; val h = 1080
        fun draw(view: android.view.View, height: Int, name: String) {
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                if (height > 0) android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
                else android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, w, view.measuredHeight)
            val bmp = Bitmap.createBitmap(w, view.measuredHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(0xFF120D0B.toInt())
            view.draw(canvas)
            write(name, bmp)
        }
        draw(root, h, "recovery")
        val scroll = (activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
        draw(scroll.getChildAt(0), 0, "recovery-full")
    }
}

/** Artwork for the showcase: posters, backdrops, logos, people, channel logos, app banners and stills, drawn from a title. */
private object Art {
    private fun hue(s: String) = ((s.hashCode() and 0x7fffffff) % 360).toFloat()
    private fun hsv(h: Float, s: Float, v: Float) = android.graphics.Color.HSVToColor(floatArrayOf(((h % 360) + 360) % 360, s, v))

    fun draw(kind: String, title: String): Bitmap = when (kind) {
        "poster" -> scene(title, 400, 600, true)
        "backdrop" -> scene(title, 1280, 720, false)
        "still" -> scene(title, 640, 360, false)
        "logo" -> logo(title)
        "person" -> badge(title, 240, 240, round = true)
        "channel" -> badge(title, 240, 240, round = false)
        "banner" -> banner(title)
        else -> scene(title, 400, 600, true)
    }

    private fun scene(title: String, w: Int, h: Int, text: Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = hue(title)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), hsv(base + 200, .55f, .22f), hsv(base + 20, .75f, .9f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        // a low sun, then three ridges, far to near
        p.color = hsv(base + 40, .35f, 1f); p.alpha = 230
        c.drawCircle(w * .68f, h * .5f, minOf(w, h) * .17f, p); p.alpha = 255
        for (layer in 0..2) {
            val path = Path(); path.moveTo(0f, h.toFloat())
            val top = h * (.55f + layer * .12f); var x = 0f; var up = layer % 2 == 0
            path.lineTo(0f, top)
            while (x < w) { x += w / (3f + layer); path.lineTo(x, top + (if (up) -1 else 1) * h * (.08f - layer * .015f)); up = !up }
            path.lineTo(w.toFloat(), h.toFloat()); path.close()
            p.color = hsv(base + 220 + layer * 10, .5f, .28f - layer * .07f); c.drawPath(path, p)
        }
        // a lone figure on the nearest ridge
        p.color = hsv(base + 220, .4f, .06f)
        c.drawRect(w * .3f, h * .74f, w * .3f + w * .012f, h * .74f + h * .05f, p); c.drawCircle(w * .306f, h * .735f, w * .011f, p)
        if (text) {
            p.color = android.graphics.Color.WHITE; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            p.textSize = w * .105f
            val words = title.uppercase().split(' '); val lines = ArrayList<String>(); var line = ""
            for (word in words) { val t = if (line.isEmpty()) word else "$line $word"; if (p.measureText(t) > w * .84f && line.isNotEmpty()) { lines += line; line = word } else line = t }
            lines += line
            var y = h * .16f
            for (l in lines.take(3)) { c.drawText(l, w * .08f, y, p); y += p.textSize * 1.08f }
            p.textSize = w * .04f; p.typeface = Typeface.DEFAULT; p.alpha = 210
            c.drawText("IN CINEMAS AND STREAMING", w * .08f, h * .94f, p)
        }
        return b
    }

    private fun logo(title: String): Bitmap {
        val b = Bitmap.createBitmap(900, 220, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 120f }
        val t = title.uppercase(); while (p.measureText(t) > 880f) p.textSize -= 4f
        c.drawText(t, 10f, 150f, p)
        p.strokeWidth = 4f; c.drawLine(10f, 185f, 10f + p.measureText(t), 185f, p)
        return b
    }

    private fun badge(name: String, w: Int, h: Int, round: Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = hue(name)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), hsv(base, .55f, .75f), hsv(base + 50, .7f, .45f), Shader.TileMode.CLAMP)
        if (round) c.drawCircle(w / 2f, h / 2f, w / 2f, p) else c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), w * .18f, w * .18f, p)
        p.shader = null; p.color = android.graphics.Color.WHITE; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        p.textSize = w * .36f; p.textAlign = Paint.Align.CENTER
        c.drawText(initials, w / 2f, h / 2f + p.textSize * .35f, p)
        return b
    }

    private fun banner(name: String): Bitmap {
        val w = 320; val h = 180
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = hue(name)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), hsv(base, .65f, .55f), hsv(base + 35, .8f, .25f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = hsv(base + 30, .3f, 1f); c.drawCircle(w * .2f, h * .5f, h * .22f, p)
        p.color = android.graphics.Color.WHITE; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); p.textSize = 30f
        var t = name; while (p.measureText(t) > w * .56f && p.textSize > 18f) p.textSize -= 1f
        c.drawText(t, w * .38f, h * .58f, p)
        return b
    }
}
