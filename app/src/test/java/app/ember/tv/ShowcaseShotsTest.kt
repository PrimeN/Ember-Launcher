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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
 * Showcase pictures of Ember's real screens, filled with public-domain and Creative Commons films and shows
 * (their art from SHOWCASE_ART, or drawn here without it), and invented channels and apps. They go to the private screenshots repo with the other UI shots.
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
                            DrawableResult(BitmapDrawable(context.resources, Art.real(data.host.orEmpty(), data.lastPathSegment.orEmpty())
                                ?: Art.draw(data.host.orEmpty(), data.lastPathSegment.orEmpty())), false, DataSource.NETWORK)
                    }
            })
        }.build()
        Coil.setImageLoader(loader)
    }
    @After fun clean() { loader.shutdown(); Coil.reset(); Ui.day = false }

    // ---- public-domain and Creative Commons titles (their art comes from SHOWCASE_ART when CI has it) ----
    private class Film(val year: String, val genres: List<String>, val runtime: String, val rating: String, val about: String, val type: String = "movie")
    private val catalog = mapOf(
        "Charade" to Film("1963", listOf("Comedy", "Mystery", "Romance"), "1h 53m", "PG", "A widow in Paris is chased by three men who want the fortune her late husband stole, and helped by a charming stranger with too many names."),
        "His Girl Friday" to Film("1940", listOf("Comedy", "Drama", "Romance"), "1h 32m", "NR", "A fast-talking newspaper editor tries to win back his ace reporter, and ex-wife, before she leaves for a quiet new life."),
        "Metropolis" to Film("1927", listOf("Sci-Fi", "Drama"), "2h 33m", "NR", "In a towering city of the future, the son of its master falls for a worker's prophet, and a machine is made in her image."),
        "The General" to Film("1926", listOf("Comedy", "Action"), "1h 19m", "NR", "A train engineer pursues his stolen locomotive deep into enemy territory in a one-man rescue mission."),
        "Nosferatu" to Film("1922", listOf("Horror", "Fantasy"), "1h 34m", "NR", "An estate agent travels to Transylvania to sell a house to Count Orlok, who follows him home and brings the plague with him."),
        "Sunrise" to Film("1927", listOf("Drama", "Romance"), "1h 34m", "NR", "A farmer tempted by a woman from the city plans a terrible crime, then rediscovers his love for his wife on a day in town."),
        "The Kid" to Film("1921", listOf("Comedy", "Drama"), "1h 8m", "NR", "The Tramp takes in an abandoned baby and raises him on the streets, until the authorities come to take the boy away."),
        "Sherlock Jr." to Film("1924", listOf("Comedy", "Mystery"), "45m", "NR", "A projectionist who dreams of being a detective falls asleep at work and walks right into the film on the screen."),
        "Safety Last!" to Film("1923", listOf("Comedy", "Thriller"), "1h 10m", "NR", "To impress his girl back home, a shop clerk stages a publicity stunt and ends up climbing a skyscraper himself."),
        "The Cabinet of Dr. Caligari" to Film("1920", listOf("Horror", "Mystery"), "1h 16m", "NR", "A hypnotist and his sleepwalker arrive at a village fair, and a string of murders follows them."),
        "Detour" to Film("1945", listOf("Crime", "Film Noir"), "1h 7m", "NR", "A pianist hitchhiking to Los Angeles takes on a dead man's identity, and a stranger who knows the truth."),
        "D.O.A." to Film("1950", listOf("Film Noir", "Thriller"), "1h 23m", "NR", "Poisoned and with days to live, an accountant races to find out who murdered him and why."),
        "Plan 9 from Outer Space" to Film("1957", listOf("Sci-Fi", "Horror"), "1h 19m", "NR", "Aliens raise the dead of Earth to stop humanity from building a doomsday weapon."),
        "Night of the Living Dead" to Film("1968", listOf("Horror"), "1h 36m", "NR", "Strangers barricade themselves in a farmhouse as the dead rise and close in."),
        "Sintel" to Film("2010", listOf("Animation", "Fantasy"), "15m", "NR", "A lonely girl searches the world for the baby dragon she raised and lost."),
        "Big Buck Bunny" to Film("2008", listOf("Animation", "Comedy"), "10m", "G", "A gentle giant rabbit takes his revenge on three forest bullies."),
        "Spring" to Film("2019", listOf("Animation", "Fantasy"), "8m", "NR", "A shepherd girl and her dog face ancient spirits to bring back the spring."),
        "Tears of Steel" to Film("2012", listOf("Sci-Fi", "Action"), "12m", "NR", "In a future Amsterdam, scientists try to save the world from robots by replaying a broken-hearted moment."),
        "Elephants Dream" to Film("2006", listOf("Animation", "Sci-Fi"), "11m", "NR", "Two men explore a vast, strange machine, and each sees it differently."),
        "Cosmos Laundromat" to Film("2015", listOf("Animation", "Comedy"), "12m", "NR", "A suicidal sheep meets a salesman who offers him the gift of a lifetime."),
        "The Gold Rush" to Film("1925", listOf("Comedy", "Adventure"), "1h 35m", "NR", "The Tramp heads to the Klondike in search of gold and finds hunger, a storm and love."),
        "The Phantom of the Opera" to Film("1925", listOf("Horror", "Drama"), "1h 33m", "NR", "A masked figure haunting the Paris Opera falls for a young soprano."),
        "House on Haunted Hill" to Film("1959", listOf("Horror", "Mystery"), "1h 15m", "NR", "A millionaire offers five guests ten thousand dollars each to survive a night in a haunted house."),
        "Carnival of Souls" to Film("1962", listOf("Horror", "Mystery"), "1h 18m", "NR", "After surviving a car crash, a church organist is drawn to an abandoned pavilion by a pale stranger."),
        "The Hitch-Hiker" to Film("1953", listOf("Film Noir", "Thriller"), "1h 11m", "NR", "Two friends on a fishing trip pick up a hitchhiker who turns out to be a killer."),
        "The Lost World" to Film("1925", listOf("Adventure", "Sci-Fi"), "1h 33m", "NR", "An expedition to a remote plateau in South America finds dinosaurs still alive."),
        "The Thief of Bagdad" to Film("1924", listOf("Adventure", "Fantasy"), "2h 20m", "NR", "A thief falls for a princess and sets out on a magical quest to win her hand."),
        "Steamboat Bill, Jr." to Film("1928", listOf("Comedy"), "1h 10m", "NR", "A college boy joins his father's riverboat business, just as a cyclone hits town."),
        "Battleship Potemkin" to Film("1925", listOf("Drama", "History"), "1h 15m", "NR", "Sailors mutiny against their officers, and the people of Odessa rise with them."),
        "A Trip to the Moon" to Film("1902", listOf("Adventure", "Sci-Fi"), "14m", "NR", "Astronomers fire themselves to the moon in a capsule and meet its strange inhabitants."),
        "The Beverly Hillbillies" to Film("1962", listOf("Comedy"), "26m", "TV-G", "A mountaineer strikes oil and moves his family to a mansion in Beverly Hills.", "series"),
        "The Lucy Show" to Film("1962", listOf("Comedy"), "25m", "TV-G", "A widowed mother and her divorced best friend share a house and endless schemes.", "series"),
        "Bonanza" to Film("1959", listOf("Western", "Drama"), "49m", "TV-PG", "Ben Cartwright and his three sons run the Ponderosa ranch near Virginia City.", "series"),
        "Superman" to Film("1941", listOf("Animation", "Action"), "10m", "TV-G", "The Man of Steel takes on mad scientists, robots and saboteurs in Technicolor.", "series"),
        "Flash Gordon" to Film("1954", listOf("Sci-Fi", "Adventure"), "26m", "TV-G", "Flash Gordon and his crew patrol the galaxy for the Galactic Bureau of Investigation.", "series"),
        "The Cisco Kid" to Film("1950", listOf("Western"), "26m", "TV-G", "A charming outlaw and his sidekick ride the Old West righting wrongs.", "series"),
    )
    private fun art(kind: String, title: String) = "fixture://$kind/${android.net.Uri.encode(title)}"
    private fun meta(title: String, year: String? = null, type: String? = null, badge: String? = null, progress: Float? = null): Meta {
        val f = catalog[title]
        return Meta(id = "show:" + Art.slug(title), type = type ?: f?.type ?: "movie", name = title, releaseInfo = year ?: f?.year ?: "",
            poster = art("poster", title), background = art("backdrop", title), logo = art("logo", title),
            description = f?.about ?: "", runtime = f?.runtime ?: "", genres = f?.genres ?: emptyList(), badge = badge, progress = progress,
            ageRating = f?.rating?.takeIf { it != "NR" })
    }
    private val films = listOf("Charade", "His Girl Friday", "Metropolis", "Sunrise", "The Cabinet of Dr. Caligari", "The General", "Nosferatu", "Safety Last!",
        "The Kid", "Detour", "Sintel", "D.O.A.", "Sherlock Jr.", "The Gold Rush", "Carnival of Souls", "Plan 9 from Outer Space")
    private val posters get() = films.map { meta(it) }
    private val shows = listOf("The Beverly Hillbillies", "The Lucy Show", "Bonanza", "The Cisco Kid", "Superman", "Flash Gordon")

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
    // #476: corner ribbons on posters and wide cards.
    @Test fun ribbons() {
        val kinds = setOf(app.ember.tv.data.PosterRibbons.Kind.NEW, app.ember.tv.data.PosterRibbons.Kind.AGE, app.ember.tv.data.PosterRibbons.Kind.GENRE)
        // #477: watched and saved as small corner icons too.
        val watched = app.ember.tv.ui.WatchedUi(showBadges = true, title = { it.name in setOf("Sunrise", "Metropolis", "Bonanza") })
        compose.setContent { Frame { CompositionLocalProvider(app.ember.tv.ui.LocalRibbonKinds provides kinds, app.ember.tv.ui.LocalWatchedUi provides watched) {
            Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ModernSection("Trending this week", "Cinemeta") {
                    MediaRow(listOf(meta("Sintel", year = "2026")) + posters.take(7), false, { it.name == "Metropolis" }) {}
                }
                ModernSection("Continue watching", null) {
                    MediaRow(shows.take(4).mapIndexed { i, t -> meta(t, type = "series", progress = .3f + i * .1f) }, true, { it.name == "The Lucy Show" || it.name == "Bonanza" }) {}
                }
            }
        } } }
        save("ribbons")
    }

    @Test fun homeDay() { Ui.day = true; compose.setContent { Frame(night = false) { HomeScene(NavbarStyles.CINEMA) } }; save("home-day") }

    @Test fun titlePage() {
        val title = meta("His Girl Friday")
        val streams = listOf(
            StreamOption("Your Plex", "1080p", "His Girl Friday (1940) Criterion restoration", "https://example.invalid/1", null, null, "FHD", "", "H.264", "FLAC 1.0", "BluRay", "", 21.4, null, true, true),
            StreamOption("Internet Archive", "1080p", "His Girl Friday 1940 1080p", "https://example.invalid/2", null, null, "FHD", "", "H.264", "AAC 2.0", "WEB", "", 2.1, null, true, true),
            StreamOption("Your Jellyfin", "720p", "His Girl Friday (1940)", "https://example.invalid/3", null, null, "HD", "", "H.264", "AC3 2.0", "DVD", "", 1.4, null, true),
        )
        val people = listOf("Howard Hawks" to "Director", "Cary Grant" to "Walter Burns", "Rosalind Russell" to "Hildy Johnson", "Ralph Bellamy" to "Bruce Baldwin", "Gene Lockhart" to "Sheriff Hartwell", "Porter Hall" to "Murphy")
            .mapIndexed { i, (n, r) -> Person(n, r, art("person", n), isCrew = i == 0) }
        val d = DetailsUi(title, streams = streams, addonCredits = Credits(people, "trailer"),
            related = RelatedTitles("Cary Grant classics", listOf(meta("His Girl Friday"), meta("Charade")), posters.drop(2).take(8)),
            parentsGuide = listOf(app.ember.tv.tracker.ParentsGuide.Entry("Violence", "Moderate"), app.ember.tv.tracker.ParentsGuide.Entry("Profanity", "Mild")),
            ratings = mapOf("imdb" to "7.8", "tmdb" to "7.6", "rt" to "99%"))
        compose.setContent { Frame { MediaPageLayoutScope(MediaPageLayouts.EMBER_X) {
            ModernBackdrop(title, dim = .35f)
            DetailsScreen(d = d, listState = rememberLazyListState(), saved = true, isContinue = false, onToggleSaved = {}, onRemoveContinue = {},
                onSeason = {}, onEpisode = {}, onMoreEpisodes = {}, onPlay = {}, onPlayWith = {}, playButton = true, best = streams.first(),
                playbackLabel = "Play", onPlayBest = {})
        } } }
        save("title")
    }

    @Test fun showPage() {
        val title = meta("The Beverly Hillbillies")
        val episodes = (1..10).map { Episode("e$it", 1, it, listOf("The Clampetts Strike Oil", "Getting Settled", "Meanwhile, Back at the Cabin", "The Clampetts Meet Mrs. Drysdale",
            "Jed Buys Stock", "Trick or Treat", "The Servants", "Jethro Goes to School", "Elly's First Date", "Pygmalion and Elly")[it - 1],
            listOf("1962-09-26", "1962-10-03", "1962-10-10", "1962-10-17", "1962-10-24", "1962-10-31", "1962-11-07", "1962-11-14", "1962-11-21", "1962-11-28")[it - 1],
            art("still", "The Beverly Hillbillies $it"), progress = if (it == 3) .45f else null,
            overview = "Jed Clampett and his kin settle into their Beverly Hills mansion, to the horror of their banker's wife.") }
        val d = DetailsUi(title, episodes = episodes, season = 2, episode = episodes[2], streams = listOf(
            StreamOption("Comet", "4K", "S01E03 1080p remaster", "https://example.invalid/e", null, null, "FHD", "", "H.264", "AAC 2.0", "WEB", "", 0.9, null, true, true)))
        compose.setContent { Frame { MediaPageLayoutScope(MediaPageLayouts.MODERN) {
            ModernBackdrop(title, dim = .35f)
            DetailsScreen(d = d, listState = rememberLazyListState(), saved = false, isContinue = true, onToggleSaved = {}, onRemoveContinue = {},
                onSeason = {}, onEpisode = {}, onMoreEpisodes = {}, onPlay = {}, onPlayWith = {}, playButton = true, best = d.streams.first(), playbackLabel = "Resume 11:40")
        } } }
        save("show")
    }

    @Test fun player() {
        val s = ControlsState().apply {
            visible = true; title = "Metropolis"; seasonEpisode = null
            details = PlayerDetails("Metropolis", logo = art("logo", "Metropolis"), year = "1927", rating = "IMDb 8.3",
                synopsis = catalog.getValue("Metropolis").about,
                cast = listOf("Brigitte Helm", "Alfred Abel", "Gustav Fröhlich").map { Person(it, null, art("person", it)) }, backdrop = art("backdrop", "Metropolis"))
            position = 52 * 60_000L + 7_000; duration = 153 * 60_000L; buffered = 70 * 60_000L
            audioTracks = listOf("Orchestral score · 5.1" to true, "Original 1927 score · Stereo" to false)
            subtitleOptions = listOf("Off" to false, "English intertitles" to true)
        }
        compose.setContent { Frame {
            coil.compose.AsyncImage(art("backdrop", "Metropolis"), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            AdvancedControls(s, ControlsActions(playPause = {}, seekBy = {}, skip = {}, openMenu = { _, _ -> }, options = { emptyList() }, next = {}, playEpisode = {},
                touched = {}, pickAudio = {}, pickSubtitle = {}))
        } }
        save("player")
    }

    // #474: the subtitle sheet with two-line rows and the style pill.
    @Test fun playerSubtitles() {
        val s = ControlsState().apply {
            visible = true; title = "Metropolis"
            position = 52 * 60_000L; duration = 153 * 60_000L
            subtitleOptions = listOf("Off" to false, "English" to true, "English SDH" to false, "English" to false, "French" to false, "German" to false)
            subtitleRows = listOf(
                app.ember.tv.player.SubtitleRow("None"),
                app.ember.tv.player.SubtitleRow("English", detail = "SRT", source = "Built in"),
                app.ember.tv.player.SubtitleRow("English", listOf("SDH"), "SRT", "Built in"),
                app.ember.tv.player.SubtitleRow("English", listOf("MATCH"), "Metropolis.1927.1080p.BluRay.x264", "OpenSubtitles"),
                app.ember.tv.player.SubtitleRow("French", detail = "Metropolis.1927.Restored.WEB-DL", source = "SubDL"),
                app.ember.tv.player.SubtitleRow("German", listOf("FORCED"), "PGS", "Built in"),
            )
            sheet = app.ember.tv.player.SUBTITLE_SHEET
        }
        compose.setContent { Frame {
            coil.compose.AsyncImage(art("backdrop", "Metropolis"), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            AdvancedControls(s, ControlsActions(playPause = {}, seekBy = {}, skip = {}, openMenu = { _, _ -> }, options = { emptyList() }, next = {}, playEpisode = {},
                touched = {}, pickAudio = {}, pickSubtitle = {}))
        } }
        save("player-subtitles")
    }

    // #474: the "Set up for this TV" card.
    @Test fun tuningCard() {
        val plan = app.ember.tv.player.DeviceTuning.plan(app.ember.tv.player.DeviceTuning.Facts(2048, listOf(23.976f, 24f, 50f, 59.94f, 60f), listOf("Dolby Digital", "TrueHD", "DTS-HD"), true,
            "NVIDIA SHIELD Android TV", "11", listOf("Dolby Vision", "HDR10"), true), app.ember.tv.player.PlaybackConfig())
        compose.setContent { Frame { CompositionLocalProvider(LocalInlineDialogs provides true) { app.ember.tv.ui.TuningCard(plan, close = {}, review = {}) } } }
        dialog("tuning-card")
    }

    private val channelNames = listOf("Northline News", "Fieldhouse Sports", "Cinemora", "Wavelet Music", "Puddle Kids", "Skyline Weather", "Reelhouse Classics", "Arcadia Games",
        "Harbor 24", "Metro One", "Lumen Docs", "Gourmet Lane", "Velocity Motors", "Atlas Travel", "Comedy Corner", "Nightjar Drama")
    private val clock = 90_900_000L
    private val channels = channelNames.mapIndexed { i, n -> Channel("a:$i", "a", n, "https://example.test/$i", logo = art("channel", n), group = "Entertainment", number = 101 + i) }
    private val showsOnAir = listOf("Evening Report", "Match of the Night", "Detour", "Late Grooves", "Puddle Pals", "Storm Watch", "Bonanza", "Pixel Cup",
        "Harbor Tonight", "City Beat", "Deep Blue Planet", "Kitchen Heat", "Track Day", "Island Hopping", "Stand-up Hour", "Carnival of Souls")
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
            coil.compose.AsyncImage(art("backdrop", "Detour"), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            LiveGuideOverlay(g, { c, _ -> programmes(c).let { it[0] to it[1] } }) { "Home playlist" }
        } }
        save("live-overlay")
    }

    @Test fun search() {
        val q = "tense black and white thrillers from the 40s and 50s"
        val why = mapOf("Detour" to "A hitchhike that goes very wrong", "D.O.A." to "A man solving his own murder", "The Hitch-Hiker" to "Two friends, one killer, no way out",
            "Carnival of Souls" to "Eerie, dreamlike and unsettling", "House on Haunted Hill" to "One night, five guests, a fortune",
            "Plan 9 from Outer Space" to "So bad it's a classic")
        val answer = why.map { (t, w) -> meta(t, badge = w) }
        val state = SearchCanvasState(profileId = "main", query = q, rows = listOf(CatalogRow("Cinemeta", "Movies", posters.take(6))),
            ai = SearchAi("Google Gemini", q, answer, false, null))
        compose.setContent { Frame { SearchCanvas(state, SearchCanvasActions({}, {}, {}, {}, {}, {}, {}, {}, {})) } }
        save("search")
    }

    @Test fun spark() {
        fun row(title: String, sub: String?, names: List<String>, why: (String) -> String) = title to Pair(sub, names.map { meta(it, badge = why(it)) })
        val rows = listOf(
            row("Top picks for you", "From what you've been watching", listOf("The Cabinet of Dr. Caligari", "Nosferatu", "A Trip to the Moon", "The Lost World", "The Thief of Bagdad", "Sunrise", "The Phantom of the Opera")) { "Because you watched Metropolis · ★ 8.0" },
            row("Silent comedy greats", null, listOf("The General", "Sherlock Jr.", "Safety Last!", "The Kid", "The Gold Rush", "Steamboat Bill, Jr.", "Big Buck Bunny")) { "You like comedy · ★ 8.1" },
        )
        compose.setContent { Frame {
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Spark", fontSize = 26.sp)
                Text("Spark has noticed you like Silent film · Comedy · Horror · 1920s · Film noir · picked Tue 24 Nov, 8:12 PM", color = Mute, fontSize = 13.sp)
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
        // App banners come from the package manager on a TV; here they are drawn and passed as custom art.
        val art = names.mapIndexed { i, n ->
            val png = java.io.ByteArrayOutputStream().also { Art.draw("banner", n).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            "app.showcase.$i" to app.ember.tv.home.AppArt("showcase-$i", java.util.Base64.getEncoder().encodeToString(png))
        }.toMap()
        compose.setContent {
            CompositionLocalProvider(app.ember.tv.ui.LocalInternetStatus provides true, app.ember.tv.ui.LocalAppArtwork provides art) {
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

    /** #447: Settings > Trackers with brand rows, Trakt focused, and the Sources choice. */
    @Test fun trackersPage() = trackers(day = false)
    @Test fun trackersPageDay() = trackers(day = true)

    private fun trackers(day: Boolean) {
        Ui.day = day
        val accounts = listOf(
            ServiceCardInfo("trakt", "Trakt", TrackerAbout.of("trakt"), "Connected as arash · synced 4 min ago", ServiceState.READY, ServiceBrands.of("trakt")),
            ServiceCardInfo("simkl", "Simkl", TrackerAbout.of("simkl"), "Not connected", ServiceState.OFF, ServiceBrands.of("simkl")),
            ServiceCardInfo("anilist", "AniList", TrackerAbout.of("anilist"), "Connected as @arash", ServiceState.READY, ServiceBrands.of("anilist")),
            ServiceCardInfo("mal", "MyAnimeList", TrackerAbout.of("mal"), "Not responding · check", ServiceState.PROBLEM, ServiceBrands.of("mal")),
            ServiceCardInfo("wetrakr", "WeTrakr", TrackerAbout.of("wetrakr"), "Needs an API key", ServiceState.ATTENTION, ServiceBrands.of("wetrakr")),
        )
        val sources = listOf(
            ServiceCardInfo("s1", "Continue watching", "Which progress powers resume and Continue watching", "All connected", ServiceState.READY),
            ServiceCardInfo("s2", "Watchlist", "Which watchlist Home and your Library show", "Trakt", ServiceState.READY, ServiceBrands.of("trakt")?.copy(logo = null)),
        )
        compose.setContent { Frame(night = !day) {
            Column(Modifier.fillMaxSize().padding(horizontal = 0.dp, vertical = 18.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceCardGroup("Accounts", "Connect and manage tracking services", accounts) {}
                ServiceCardGroup("Sources", "Choose where Alex's lists come from. What you watch is still reported to every connected tracker.", sources) {}
            }
        } }
        compose.onAllNodesWithTag("service_card")[0].requestFocus()
        save(if (day) "trackers-day" else "trackers", settle = 600)
    }

    /** #447: media servers and API keys as brand rows. */
    @Test fun serversAndKeysPage() {
        val servers = MediaServerTiles.cards(plex = true, plexSignedOut = false, plexUser = "Arash",
            listOf(MediaServerTiles.Server("j", "Living room", "jellyfin", "arash", signedOut = false, failing = false),
                MediaServerTiles.Server("e", "Cottage", "emby", "", signedOut = true, failing = false)))
        val keys = listOf("TMDB", "MDBList", "TVDB", "OpenAI", "Groq").mapNotNull { n -> app.ember.tv.data.ApiKeys.SERVICES.firstOrNull { it.name == n } }
            .mapIndexed { i, s -> keyCard(s).copy(status = if (i < 3) "Key set" else "No key", state = if (i < 3) ServiceState.READY else ServiceState.OFF) }
        compose.setContent { Frame {
            Column(Modifier.fillMaxSize().padding(vertical = 18.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceCardGroup("Servers", "Your Plex account, and Jellyfin or Emby servers", servers) {}
                ServiceCardGroup("Optional keys", "Extra artwork, ratings, subtitles, AI and more", keys) {}
            }
        } }
        compose.onAllNodesWithTag("service_card")[0].requestFocus()
        save("servers-keys", settle = 600)
    }

    /** #468: the collection editor and its folder editor, in the widget editor's style. */
    @Test fun collectionEditor() {
        val vm = HomeViewModel(context)
        val source = { id: String -> app.ember.tv.data.CollectionSource(addonId = "aio-metadata", type = "movie", catalogId = id) }
        val collection = app.ember.tv.data.MediaCollection("showcase", "Streaming services", folders = listOf(
            app.ember.tv.data.CollectionFolder("f1", "Classics", tileShape = "LANDSCAPE", sources = listOf(source("a"), source("b"))),
            app.ember.tv.data.CollectionFolder("f2", "Noir", coverEmoji = "🎞", sources = listOf(source("c"))),
            app.ember.tv.data.CollectionFolder("f3", "Silent era", tileShape = "POSTER", sources = listOf(source("d"), source("e"), source("f"))),
            app.ember.tv.data.CollectionFolder("f4", "My watchlist", tileShape = "LANDSCAPE",
                sources = listOf(app.ember.tv.data.LibraryLists.source(app.ember.tv.data.LibraryLists.all.first()))),
        ), showAllTab = true)
        compose.setContent { Frame { CompositionLocalProvider(LocalInlineDialogs provides true) { app.ember.tv.ui.CollectionEditor(vm, collection) {} } } }
        dialog("collection-editor")
    }

    @Test fun folderEditor() {
        val vm = HomeViewModel(context)
        val source = { id: String -> app.ember.tv.data.CollectionSource(addonId = "aio-metadata", type = "movie", catalogId = id) }
        val folder = app.ember.tv.data.CollectionFolder("f1", "Classics", tileShape = "LANDSCAPE", coverEmoji = "🎞",
            sources = listOf(source("a"), app.ember.tv.data.LibraryLists.source(app.ember.tv.data.LibraryLists.all.first()),
                app.ember.tv.data.CollectionSource(provider = "trakt", traktListId = 1L, title = "Film noir essentials")))
        compose.setContent { Frame { CompositionLocalProvider(LocalInlineDialogs provides true) { app.ember.tv.ui.CollectionFolderEditor(vm, folder, onSave = {}) {} } } }
        dialog("folder-editor")
        compose.onNodeWithTag("folder_step_sources").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        dialog("folder-sources")
    }

    /** #469: the source picker, catalog filters and source editor in the editors' style. */
    @Test fun sourcePicker() {
        val vm = HomeViewModel(context)
        compose.setContent { Frame { CompositionLocalProvider(LocalInlineDialogs provides true) {
            app.ember.tv.ui.CollectionSourcePicker(vm, onChoose = {}, close = {}, onChooseMany = {}) } } }
        compose.onNodeWithTag("source_kind_Your lists").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("library_watchlist").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("library_smart:upnext").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        dialog("source-picker")
        compose.onNodeWithTag("source_kind_TMDB").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        dialog("source-tmdb")
    }

    @Test fun catalogFilters() {
        val addon = app.ember.tv.data.AddonInfo("https://example.invalid/manifest.json", "https://example.invalid", "Cinemeta", listOf("movie"), null, emptyList(), emptyList())
        val def = app.ember.tv.data.CatalogDef(addon, "movie", "top", "Popular", listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "Western"), false, false, false, false,
            extras = listOf(app.ember.tv.data.CatalogExtraDef("genre", options = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family",
                "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "Western"), limit = 3),
                app.ember.tv.data.CatalogExtraDef("year", options = (2026 downTo 2015).map { "$it" })))
        compose.setContent { Frame { CompositionLocalProvider(LocalInlineDialogs provides true) {
            app.ember.tv.ui.CatalogOptions(def, mapOf("genre" to "Comedy"), {}) {} } } }
        dialog("catalog-filters")
    }

    /** A dialog is its own window: the newest root. */
    private fun dialog(name: String) {
        save(name, settle = 400)
    }
}

/** Artwork for the showcase: posters, backdrops, logos, people, channel logos, app banners and stills, drawn from a title. */
private object Art {
    /**
     * Free film art (public domain or Creative Commons) when CI provides it: SHOWCASE_ART names a folder of
     * <kind>/<slug>.jpg files from the private screenshots repo. Anything missing is drawn instead.
     */
    private val folder: java.io.File? = System.getenv("SHOWCASE_ART")?.let { java.io.File(it) }?.takeIf { it.isDirectory }
    fun slug(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    fun real(kind: String, title: String): Bitmap? {
        val dir = folder ?: return null
        val file = listOf("jpg", "png").map { java.io.File(dir, "$kind/${slug(title)}.$it") }.firstOrNull { it.isFile } ?: return null
        return android.graphics.BitmapFactory.decodeFile(file.path)
    }
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
