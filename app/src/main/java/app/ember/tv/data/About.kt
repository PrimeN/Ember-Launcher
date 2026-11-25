package app.ember.tv.data

/** Text for Settings > About. */
object About {
    const val AUTHOR = "Arash N"

    val WHY = listOf(
        "Ember is an ad-free home screen for Google TV and Android TV. Your apps, your watchlist, your own media servers and your Stremio addons live together on one Home you arrange yourself, with no sponsored rows and no promotions.",
        "I built Ember for a home cinema: a big screen and a proper sound system. It finds the best source for a title, plays it with lossless audio straight to the receiver at the film's own frame rate, and keeps Trakt and your other trackers up to date along the way.",
    )

    data class Release(val version: String, val title: String, val changes: List<String>)

    /** Newest first. */
    val CHANGELOG = listOf(
        Release(
            "1.4.1", "Squished bugs, plus a safety net",
            listOf(
                "Squished a batch of bugs for a smoother, steadier start.",
                "New recovery page: if Ember can't start three times in a row, it opens a simple page instead.",
                "From recovery: update to the latest Ember, pick a specific release, or install any app from a link.",
                "Recovery can also turn Google TV's Home back on, switch on debugging (ADB), and open TV settings, All apps and Developer options.",
            ),
        ),
        Release(
            "1.4.0", "Meet Spark",
            listOf(
                "Spark in Discover: picks from what each profile watches, in named rows like Mind-bending sci-fi and Because you watched, each with its reason.",
                "Bring in your whole Trakt, Simkl or WeTrakr history and ratings so Spark knows you from day one; keep Trakt's full details and refresh it daily or weekly.",
                "Spark's rows can be Home widgets, and follow your taste as it changes.",
                "One AI setting for Google Gemini, Groq, OpenRouter, Mistral, Cerebras, OpenAI, Claude or your own server, with free tiers and a model picker.",
                "Ask Spark in plain words, on its page or straight from Search; only real titles you haven't seen come back, and answers can be saved as rows.",
            ),
        ),
        Release(
            "1.3.0", "Set up for your TV",
            listOf(
                "On first start Ember sets the buffer and frame-rate matching for your box and screen, and \"Set up for this TV\" in Player settings does it again any time.",
                "A file much shorter or longer than the title stops and asks, catching samples, trailers, fakes and wrong episodes.",
                "Optional poster ribbons (New, age rating, movie or series, genre, year, running time), drawn by Ember with no key or service needed.",
                "A notifications bell with a count beside the settings buttons in quick settings.",
                "Live TV: press Left while watching for a channel guide over the video, with what's on now and next.",
            ),
        ),
        Release(
            "1.2.0", "Better sound, smarter sources",
            listOf(
                "Surround stays surround: formats your receiver can't take are decoded to 5.1 or 7.1, a soundbar mode for TVs with a soundbar on ARC, and a format whose passthrough keeps failing is decoded instead of going silent.",
                "After the TV switches frame rate, playback waits for its sound to come back, so the first seconds aren't lost.",
                "A source that won't start is skipped for the next ready one, sources for Continue watching are found before you open the title, and each add-on shows OK, Slow or Down.",
                "Title page: Collection and More like this rows, IMDb's parents guide, and a full page for cast and crew.",
                "Continue watching shows how many episodes are left, and watched titles on Home can be shown, dimmed or hidden.",
                "Seek thumbnails for streamed files, and a fuller stream info panel with colour-coded health.",
                "Focus glow can take each title's own colours, and settings pages have tabs and say why an option is unavailable.",
                "System access and optional whole-TV ad blocking with your own NextDNS profile.",
                "A hardware decoder taken by another app is tried again instead of being replaced, plus playback refinements from Ember Player.",
            ),
        ),
        Release(
            "1.1.0", "Ready for everyone",
            listOf(
                "New navbar styles: Ember Collapsed with a matte menu, and a Sidebar; your profile now sits in the menu.",
                "Player settings: Simple shows the everyday options, Advanced shows them all; longer explanations open from an info button.",
                "Subtitle style set step by step with a live preview; the audio track starts in your language by default.",
                "Ember Light: the background follows the row you are on, colours follow your theme, and apps' live sports and events can be hidden.",
                "Advanced badge settings: order badge categories and switch them on or off.",
                "Addons, catalogs and Discover settings as compact cards; addon settings open on your phone by QR code.",
                "Collections sit and sort with your Home widgets; create one from Create widget. Startup moved to System.",
                "Files the main player cannot open move to compatibility playback again, and a crash in the audio effects is fixed.",
                "Widgets for your apps' Continue watching and your recently used apps.",
                "Rename apps, and automatic weekly backups kept on the TV.",
                "Notifications in quick settings, a full-screen clock, a choice for what Back does on Home, and a screensaver clock that moves to protect the screen.",
                "Player: smoother seeking, night listening, network and buffer settings, compatibility decoding, and compatibility playback updated to mpv 0.42 and FFmpeg 9 with the gpu-next renderer.",
                "Skip intros and recaps marked by a file's own chapters, and remote buttons for captions, audio, stop and jumping ahead.",
                "Settings: search finds individual options, every page can be reset, and pages are reorganized.",
                "Addons can be reordered; pick several catalogs at once for a widget or folder.",
                "Dates, times and counts follow your TV's language and clock everywhere.",
                "Consistent wording, clearer quick settings and a setup checklist for new installs.",
                "Fixed resetting the Extras page clearing app PIN locks.",
            ),
        ),
        Release(
            "0.9.9", "Home widgets and a phone remote",
            listOf(
                "Everything on Home is a widget: your own rows, numbered lists, carousels and banners.",
                "A step-by-step widget creator with a preview; widgets move between TVs as a file.",
                "Phone remote: an installable web app with search, playback, sound and subtitles.",
                "Discover: new filters and sorting, more awards, genre tiles and photo pickers.",
                "Settings show pictures for choices, and sliders instead of rows of numbers.",
                "Edit badge patterns and icons on the TV or the phone.",
                "Up next card in the corner, with a countdown you set.",
                "Collections with previews, and Home organized from the phone.",
                "Profile colours and photos; tiles for trackers, servers and API keys.",
                "Crash log on the phone, and screen-reader labels.",
                "Fixed a crash when a downloaded subtitle cannot be loaded.",
            ),
        ),
        Release(
            "0.9.8", "Trackers in sync and a cleaner Home",
            listOf(
                "Fixed freezes when addons refresh or a catalog is renamed.",
                "Keep Trakt, Simkl and WeTrakr in sync: watched history, watchlist and ratings.",
                "WeTrakr rebuilt, and Simkl syncs far more efficiently.",
                "Rate titles, and mark watched or unwatched, on your trackers.",
                "Watched marks from your full tracker history; offline changes are sent later.",
                "Jellyfin Quick Connect and Next up, ready for Jellyfin 10.12.",
                "Light interface: app rows, a carousel catalog and matching glass.",
                "Any catalog can feed the hero or the carousel.",
                "Addon details in Settings: logo, version, catalogs and setup links.",
                "New cast & crew popup.",
                "Home opens on the hero, and the first remote press works after the TV wakes.",
                "Updates install more reliably, without downloading twice.",
                "Lists load more as you scroll, with more consistent switches, dialogs and menus.",
                "Phone setup with tabs and every setting.",
            ),
        ),
        Release(
            "0.9.7", "Faster and more polished",
            listOf(
                "Faster startup, scrolling and artwork loading, especially on Chromecast with Google TV and similar devices.",
                "Improved stability, and the crash log now also records freezes and memory restarts.",
                "New startup screen and refreshed Ember branding.",
                "Smoother glass effects that follow your theme.",
                "More consistent buttons, focus effects and easier-to-read text.",
                "New option to hide the labels under apps, titles and collections.",
                "Smaller default interface size, with steps either side.",
                "Fixed server artwork and Library backgrounds.",
            ),
        ),
        Release(
            "0.9.6", "Public updates and polished navigation",
            listOf(
                "Standard and beta updates use public Ember downloads without a GitHub token. Beta can choose published Releases or the latest successful signed Actions APK.",
                "Beta source preferences are saved and backed up. Actions downloads verify the archive checksum, safely extract the APK and handle expired or unavailable builds.",
                "System cache management: set image, metadata, catalog and network retention, inspect cache size and clear cached data without removing accounts or personal lists.",
                "Catalog and collection artwork stays cached on disk for quicker loading and refreshes when its configuration changes.",
                "Adaptive glass styling uses lightweight tint, cached blur or supported edge refraction, with corrected navbar backdrop alignment.",
                "Profile and action menus share performance-aware background dimming and blur. Home hero controls scroll away cleanly and release hidden focus.",
                "More reliable startup D-pad navigation, playback identities, source transitions and configuration restore validation.",
            ),
        ),
        Release(
            "0.9.5.1", "Simpler in-app updates",
            listOf(
                "Check for and download public Ember updates without a GitHub account, API key or token.",
                "Release, Every build and Beta channels use public APK downloads with matching release notes.",
                "More reliable detection of newer builds, with download integrity checks and clearer request-limit messages.",
            ),
        ),
        Release(
            "0.9.5", "Your TV, your way",
            listOf(
                "Modern, Light and Ember X Home interfaces, custom heroes, app folders, themes and independent player layouts.",
                "Discover Top 100 with genre, year, rating, cast, director, studio, service and Oscar filters; optional Mistral suggestions.",
                "Editable collections, animated covers, individual catalog ordering and visibility, and configurable technical source badges.",
                "Live TV guide with playlist and group browsing, favourites, custom channel lists and hidden channels.",
                "Custom Library rows and server heroes, server-user switching, combined personal lists and watched/spoiler controls.",
                "Internal Compatibility player, network folders, queues, repeat controls, dual subtitles and optional IntroDB skip markers.",
                "More playback recovery and codec options, profile-aware HDR handling and optional video deinterlacing.",
                "Profile app locks, child-content filters, administrator PIN controls and timed phone setup with TV approval.",
                "Optional Fanart.tv artwork, Streaming Availability lookups and additional weather providers.",
                "Performance presets and custom rendering budgets, calibration clips and supported firmware/input shortcuts.",
                "Startup destinations, photo/video presentations and supported apps' Now Playing controls.",
            ),
        ),
        Release(
            "0.9", "Home cinema",
            listOf(
                "Built-in player for high-end setups: Dolby TrueHD, Atmos and DTS-HD sent straight to your receiver, FFmpeg decoding for audio the TV can't play, and a choice of formats for receivers on ARC.",
                "Frame-rate matching (23.976 films at 23.976 Hz), optional resolution matching, larger buffers for 4K remuxes and automatic choice of the best audio track.",
                "Playback info panel: video and audio formats, HDR type, decoder, passthrough, display mode and buffer.",
                "Subtitles from your addons, OpenSubtitles and SubDL, matched to the exact file, ranked by release and picked for you, with live timing adjustment and your own style.",
                "Trailers, plus an experimental option to play them inside Ember in up to 4K.",
                "Legal and About pages.",
            ),
        ),
        Release(
            "0.8", "Make it yours",
            listOf(
                "Settings rebuilt as pages in a side list, grouped by topic.",
                "API keys entered on the TV, stored encrypted, for every service that needs one.",
                "Backup and restore of everything (settings, addons, sign-ins and keys), with an optional passphrase, to the TV, a file or a link.",
                "Posters with ratings from RPDB or Better Posters, each with its own options and a live preview.",
                "Ratings on title screens from IMDb, Trakt, TMDB, Metacritic, Rotten Tomatoes (critics and audience) and AniList, in the order you choose.",
                "Richer details from AniList for anime and TVDB for TV shows.",
                "Remote-friendly text fields that never trap the D-pad.",
            ),
        ),
        Release(
            "0.7", "Stremio power",
            listOf(
                "Custom source formatting with name and description templates, compatible with AIOStreams' formatter.",
                "Better cache detection for debrid sources (Torrentio, Comet, Debridio, AIOStreams and more).",
                "Turn each catalog on or off for Home and Discover, and put them in any order.",
                "Rename addons.",
                "Cast and crew on title screens, and running time and languages on every source.",
            ),
        ),
        Release(
            "0.6", "Where to watch",
            listOf(
                "Title screens list the streaming services that have a title in your region, from JustWatch (via TMDB) or Watchmode, and open it in the service's app.",
                "Choose your services and region; installed apps are on automatically.",
                "One list of sources: streaming services, your media servers and addons, labelled by quality (4K, FHD, HD, SD) and how they play (direct, web, un-cached).",
            ),
        ),
        Release(
            "0.5", "Your own media",
            listOf(
                "Plex, Jellyfin and Emby: sign in, find servers on your network, and get Continue and Recently added rows on Home.",
                "Copies of a title on your servers appear beside addon sources, and progress is reported back to the server.",
                "Built-in player with resume, subtitles and automatic next episode, or play with any installed player.",
            ),
        ),
        Release(
            "0.4", "Tracking",
            listOf(
                "Trakt, Simkl and WeTrakr sign-in with a code on your phone: Continue watching, watchlist sync and scrobbling.",
                "AniList and MyAnimeList lists by username.",
                "Recommended, Because you watched, Hidden gems and your Trakt lists as Home rows.",
            ),
        ),
        Release(
            "0.3", "Discover and search",
            listOf(
                "Discover page: browse any addon catalog by genre, page after page.",
                "Library page with what you're watching, your history and your watchlist.",
                "Search across addons, apps and your watchlist, with voice search and the remote's search key.",
            ),
        ),
        Release(
            "0.2", "Addons",
            listOf(
                "Stremio-compatible addons: catalogs as Home rows, title details with seasons and episodes, and sources sorted by quality.",
                "Your own watchlist, saved on the TV.",
                "Weather in the top bar, and a screensaver with your Home backdrops, classic paintings, space pictures or your own photos.",
            ),
        ),
        Release(
            "0.1", "First light",
            listOf(
                "An ad-free home screen for Google TV and Android TV.",
                "Your apps on Home: move, hide and launch them.",
                "A top menu you can reorder, a rotating banner and a choice of accent colours.",
            ),
        ),
    )
}
