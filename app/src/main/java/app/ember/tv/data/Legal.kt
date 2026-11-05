package app.ember.tv.data

/** Text for Settings > Legal. Kept in one place so it is easy to review and update. */
object Legal {
    const val APP = "Ember"
    const val UPDATED = "4 November 2026"

    data class Item(val title: String, val text: String, val link: String? = null, val licenseAsset: String? = null)
    data class Group(val title: String, val items: List<Item>, val footer: String? = null)

    // ---------------------------------------------------------------- Privacy policy

    val PRIVACY: List<Group> = listOf(
        Group(
            "In short",
            listOf(
                Item(
                    "No account, no tracking, no ads",
                    "$APP has no servers of its own, no sign-up, no analytics, no crash reporting and no advertising. " +
                        "The developer receives nothing about you or what you watch.",
                ),
                Item(
                    "Your data stays on this TV",
                    "Settings, your watchlist, Home layout, playback positions and subtitle choices are stored only on this device. " +
                        "Sign-ins and API keys are stored encrypted with the Android Keystore. Android's cloud backup is switched off for $APP.",
                ),
            ),
        ),
        Group(
            "What leaves this TV, and only when you turn it on",
            listOf(
                Item(
                    "Stremio addons",
                    "When you browse or open a title, the addons you added receive the title's id (for example an IMDb id) and the type, " +
                        "the same as the Stremio app sends. Each addon is run by a third party with its own terms.",
                ),
                Item(
                    "Trackers (Trakt, Simkl, WeTrakr, AniList, MyAnimeList)",
                    "If you connect one, $APP reads your lists and progress and sends what you play (title, episode, progress) so it can update your history.",
                ),
                Item(
                    "Your media servers (Plex, Jellyfin, Emby)",
                    "$APP talks directly to your servers and Plex's sign-in service, and reports playback progress back to your server.",
                ),
                Item(
                    "Optional IntroDB fallback",
                    "Off by default; nothing is sent until you turn it on in Settings > Player > Skip intro. When it is on and Skip intro is not Off, " +
                        "a title with no skip markers from your server sends its IMDb ID and, for episodes, the season and episode numbers to api.introdb.app. " +
                        "IntroDB is an independent community service, not run by or affiliated with $APP's developer; like any website it sees this network's public IP address, " +
                        "and its own privacy policy applies. No account tokens, media URLs, filenames or playback positions are sent, and $APP never submits timings.",
                    "https://introdb.app",
                ),
                Item(
                    "Metadata, ratings, posters and where to watch",
                    "TMDB, TVDB, AniList, MDBList, OMDb, RPDB, BetterPosters, Watchmode, Streaming Availability (through RapidAPI) and TMDB's JustWatch data receive the ids or names " +
                        "of titles you open, only for the services you switch on or give a key. Explore also sends your discovery filters and person/company searches to TMDB. Selecting an Oscar filter sends candidate movie IDs to Wikidata; no tracker account or watch history is sent.",
                ),
                Item(
                    "Optional AI-assisted discovery",
                    "Only when you enable AI-assisted discovery and request a suggestion, Mistral receives your typed description, country, current date and public filter schema. " +
                        "$APP does not send your profile or watch history, and does not save the prompt or reply. Suggested names are resolved through TMDB. " +
                        "You review ordinary filters before applying them. Mistral's own data policies and plan charges apply; manual discovery remains available without it.",
                ),
                Item(
                    "Subtitles",
                    "OpenSubtitles and SubDL receive the title's id and, if \"Match to the exact file\" is on, a hash of the video " +
                        "($APP reads 128 KB of the file to compute it; the video itself is never uploaded). OpenSubtitles.com receives your login if you enter one.",
                ),
                Item(
                    "Weather and location",
                    "The selected weather source receives the coordinates of the city you choose. With fallback enabled, Open-Meteo, MET Norway and wttr.in may be tried in order. The last forecast is cached on this TV. \"Use my approximate area\" asks ipapi.co, which sees this network's public IP address. Weather is off until you set it.",
                ),
                Item(
                    "Light Home and search",
                    "Light can read participating apps’ Watch Next and recommendations from Android’s TV provider after permission is granted. " +
                        "They remain on this TV; blocked apps are excluded, and external recommendations are hidden in child profiles. " +
                        "Light title search also sends your search words to the public Cinemeta catalog; no account or API key is needed. " +
                        "Each service’s own privacy policy applies.",
                ),
                Item(
                    "Trailers",
                    "Trailers open in the YouTube app, or (experimental option) are fetched from YouTube by $APP; YouTube sees the request either way.",
                ),
                Item(
                    "Home customization and optional media tools",
                    "Home layouts, folders, app artwork and carousel choices are stored on this TV and included in settings backups. App artwork is saved as a resized copy. " +
                        "App PIN locks protect Ember app launches and supported media handoffs across profiles; Android system settings and other launchers remain separate. " +
                        "Optional Now Playing uses Android notification-listener authorization only to read active media-session metadata and send playback controls; Ember does not save or upload notification content or listening history. " +
                        "Presentation reads selected photos/videos or locally uploaded files. Phone uploads use the existing temporary session and require approval on the TV. Settings backups retain the playlist, not large presentation media files or document permissions.",
                ),
                Item(
                    "Phone setup",
                    "Phone setup is off by default. Turning it on in Settings starts a local setup session protected by a temporary code. It turns off after 15 minutes, when you leave the page or when Ember leaves the foreground; each new session has a new code. What you send from your phone goes straight to the TV. Backups downloaded there hold your sign-ins and keys, encrypted only when you give a passphrase.",
                ),
                Item(
                    "Screensaver",
                    "Art and space pictures are loaded from the Cleveland Museum of Art and NASA. Your own pictures are read on this TV and never uploaded.",
                ),
            ),
            "Every service above has its own privacy policy, which applies to what it receives. $APP sends each one only what it needs to do its job.",
        ),
        Group(
            "Backups",
            listOf(
                Item(
                    "Backup files",
                    "A backup holds your settings, sign-ins and API keys. With a passphrase it is encrypted (AES-256-GCM); without one, anyone who has the file can use them. " +
                        "Files are saved where you choose and never sent anywhere by $APP.",
                ),
            ),
        ),
        Group(
            "Your control",
            listOf(
                Item(
                    "Removing your data",
                    "Disconnect a tracker or server in Settings > Accounts, remove a key in Settings > API keys, or clear $APP's storage in the TV's app settings to delete everything. " +
                        "Data already held by a connected service is managed in that service's account settings.",
                ),
                Item("Children", "$APP is not directed at children and collects no personal information from anyone."),
                Item("Changes", "If this policy changes, the new version is shown here with its date. Last updated $UPDATED."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Open source

    val OPEN_SOURCE: List<Group> = listOf(
        Group("Display calibration samples", listOf(Item("Mehanik and HDR Test Patterns (YouTube)",
            "Thank you to Mehanik and the HDR Test Patterns YouTube channel for the display test videos. Ember includes the original HDR10 and Dolby Vision samples for offline calibration, with encoded HDR metadata unchanged. These video assets belong to their creators. Creator attribution is bundled with the app."))),

        Group(
            "Playback",
            listOf(
                Item("AndroidX Media3 (ExoPlayer)", "Built-in player: video, audio, HLS, DASH and subtitles. Apache License 2.0.", "https://github.com/androidx/media"),
                Item(
                    "Nextlib",
                    "FFmpeg audio decoders (TrueHD, DTS, Dolby Digital, FLAC and more) for Media3, by Anil Beesetti. GNU GPL v3.",
                    "https://github.com/anilbeesetti/nextlib",
                ),
                Item("libmpv for Android 1.0.0", "Compatibility playback inside Ember. Android wrapper: MIT, copyright Ilya Zhuravlev, sfan5 and contributors. Native mpv/FFmpeg stack includes GPL v3 components; full license texts are bundled with Ember.", "https://github.com/jarnedemeulemeester/libmpv-android/tree/fcf6745703dc1265bca88f12fee8fc355ddf251e"),
                Item("FFmpeg and native player components", "The compatibility build uses mpv 0.41.0, FFmpeg 8.1, libass 0.17.4, dav1d, libplacebo and font/TLS libraries. FFmpeg is built with GPL and version3 enabled. Exact dependencies and Ember modifications are documented with the source.", "https://github.com/PrimeN/Ember-Launcher/blob/beta/player-dependencies.md"),
                Item("Fraunhofer MPEG-H decoder 3.0.2",
                    "MPEG-H software audio fallback for Standard playback through AndroidX Media3. The bundled decoder outputs stereo PCM. The complete, unmodified Fraunhofer source archive and Ember's Media3/JNI changes are available with Ember's source. The full Fraunhofer license is available below; Media3's wrapper is Apache License 2.0.",
                    "https://github.com/Fraunhofer-IIS/mpeghdec/blob/a315faa241dddcef7f849d288e89b810b5a81619/LICENSE.txt",
                    "licenses/Fraunhofer-FDK-MPEG-H.txt"),
                Item("SMBJ, JSch and Apache Commons Net", "SMB3, SFTP, WebDAV, FTP and FTPS file access. SMBJ and Commons Net: Apache License 2.0. JSch: BSD style license.", "https://github.com/PrimeN/Ember-Launcher/blob/beta/player-dependencies.md"),
                Item(
                    "NewPipe Extractor",
                    "Experimental in-app trailers: finds YouTube trailer streams. GNU GPL v3.",
                    "https://github.com/TeamNewPipe/NewPipeExtractor",
                ),
            ),
            "Because $APP includes GPL v3 components, its source must be offered under the GPL v3 if the app is ever distributed to others.",
        ),
        Group(
            "App",
            listOf(
                Item("Jetpack Compose and Compose for TV", "User interface. Apache License 2.0.", "https://developer.android.com/jetpack/androidx"),
                Item("AndroidX Core, Activity, Lifecycle, Security", "App framework and encrypted storage for sign-ins and keys. Apache License 2.0.", "https://developer.android.com/jetpack/androidx"),
                Item("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Language and libraries. Apache License 2.0.", "https://kotlinlang.org"),
                Item("OkHttp", "Networking. Apache License 2.0.", "https://square.github.io/okhttp/"),
                Item("Coil", "Image loading. Apache License 2.0.", "https://coil-kt.github.io/coil/"),
                Item("ZXing", "QR code for Settings > Phone setup. Apache License 2.0.", "https://github.com/zxing/zxing"),
                Item("AndroidX ProfileInstaller", "Installs startup profiles so the app starts faster. Apache License 2.0.", "https://developer.android.com/jetpack/androidx"),
            ),
        ),
    )

    // ---------------------------------------------------------------- Connected services

    val SERVICES: List<Group> = listOf(
        Group(
            "Metadata and artwork",
            listOf(
                Item("Wikidata", "Recorded Oscar wins and nominations for Discover. Structured data under CC0; community-maintained coverage can be incomplete.", "https://www.wikidata.org/wiki/Wikidata:Licensing"),
                Item("TMDB", "This product uses the TMDB API but is not endorsed or certified by TMDB. Metadata, images, cast and trailers.", "https://www.themoviedb.org"),
                Item("TheTVDB", "Metadata provided by TheTVDB. Please consider adding missing information or subscribing. $APP is not endorsed by TheTVDB.", "https://thetvdb.com"),
                Item("AniList", "Anime metadata and ratings provided by AniList. $APP uses the AniList API but is not endorsed or certified by AniList.", "https://anilist.co"),
                Item("Rating Poster Database (RPDB)", "Rating poster artwork provided by RPDB. $APP uses the RPDB API but is not endorsed or certified by RPDB.", "https://ratingposterdb.com"),
                Item("BetterPosters", "Poster artwork may be generated by BetterPosters, a third-party poster service. $APP uses its public poster URLs but is not endorsed or certified by BetterPosters.", "https://btttr.cc"),
            ),
        ),
        Group(
            "Ratings",
            listOf(
                Item("MDBList", "Ratings provided by MDBList. $APP uses the MDBList API but is not endorsed or certified by MDBList.", "https://mdblist.com"),
                Item("OMDb API", "IMDb, Rotten Tomatoes and Metacritic ratings via the OMDb API. Not endorsed by OMDb.", "https://www.omdbapi.com"),
                Item(
                    "IMDb, Rotten Tomatoes, Metacritic",
                    "Ratings from these sites are shown as provided by MDBList, OMDb or your addons. $APP is not affiliated with IMDb, Rotten Tomatoes (Fandango) or Metacritic (Fandom).",
                ),
            ),
        ),
        Group(
            "Tracking",
            listOf(
                Item("Trakt", "Watch history, progress and lists. $APP uses the Trakt API but is not endorsed or certified by Trakt.", "https://trakt.tv"),
                Item("Simkl", "Watch history and lists. Not endorsed by Simkl.", "https://simkl.com"),
                Item("WeTrakr", "Watch history and lists. Not endorsed by WeTrakr.", "https://wetrakr.com"),
                Item("MyAnimeList", "Anime lists by username. Not endorsed by MyAnimeList.", "https://myanimelist.net"),
            ),
        ),
        Group(
            "Where to watch",
            listOf(
                Item("JustWatch (via TMDB)", "Streaming availability data from JustWatch, provided through TMDB. Not endorsed by JustWatch.", "https://www.justwatch.com"),
                Item("Watchmode", "Streaming availability, prices and app links. Not endorsed by Watchmode.", "https://www.watchmode.com"),
                Item("Streaming Availability", "Optional regional availability, prices, quality and service links through RapidAPI. Requires your provider plan; not affiliated with or endorsed by Movie of the Night or RapidAPI.", "https://www.movieofthenight.com/about/api"),
            ),
        ),
        Group(
            "AI-assisted discovery",
            listOf(Item("Mistral AI", "Optional descriptions converted to reviewable Discover filters. Off by default; your provider's terms and charges apply. Not affiliated with or endorsed by Mistral AI.", "https://mistral.ai")),
        ),
        Group(
            "Subtitles",
            listOf(
                Item("OpenSubtitles", "Subtitles from OpenSubtitles.com and its Stremio addon. Not endorsed by OpenSubtitles.", "https://www.opensubtitles.com"),
                Item("SubDL", "Subtitles from SubDL. Not endorsed by SubDL.", "https://subdl.com"),
            ),
        ),
        Group(
            "Personal media servers",
            listOf(
                Item("Plex", "Connects to your own Plex Media Server for personal media playback. Plex and Plex Media Server are trademarks of Plex, Inc. $APP is not affiliated with or endorsed by Plex.", "https://www.plex.tv"),
                Item("Jellyfin", "Connects to your own Jellyfin server for personal media playback. Not affiliated with the Jellyfin project.", "https://jellyfin.org"),
                Item("Emby", "Connects to your own Emby server for personal media playback. Emby is a trademark of Emby LLC; not affiliated with Emby.", "https://emby.media"),
            ),
        ),
        Group(
            "Other",
            listOf(
                Item("Stremio addons", "Catalogs, details, streams and subtitles come from the addons you add. Stremio is a trademark of Stremio; $APP is not affiliated with Stremio.", "https://www.stremio.com"),
                Item("IntroDB", "Optional community intro, recap and credits timings, used only when you turn on the IntroDB fallback and your server has no skip markers. Off by default. Not affiliated with or endorsed by IntroDB.", "https://introdb.app"),
                Item("YouTube", "Trailers. YouTube is a trademark of Google LLC; not affiliated with or endorsed by Google.", "https://www.youtube.com"),
                Item("MET Norway", "Optional weather forecasts from the Norwegian Meteorological Institute, licensed under CC BY 4.0.", "https://api.met.no"),
                Item("wttr.in", "Optional weather source; its service and underlying providers’ attribution and terms apply.", "https://wttr.in"),
                Item("Open-Meteo", "Weather data by Open-Meteo.com, licensed under CC BY 4.0.", "https://open-meteo.com"),
                Item("ipapi", "Approximate location from the network's IP address, only when you ask for it.", "https://ipapi.co"),
                Item("Cleveland Museum of Art", "Screensaver paintings from the museum's Open Access collection (CC0).", "https://www.clevelandart.org/open-access"),
                Item("NASA Image and Video Library", "Screensaver space pictures. NASA does not endorse $APP.", "https://images.nasa.gov"),
            ),
            "All metadata, images, and related content are owned by their respective providers and rights holders.",
        ),
    )

    // ---------------------------------------------------------------- Disclaimer

    val DISCLAIMER: List<String> = listOf(
        "All metadata, images, and related content are owned by their respective providers and rights holders.",
        "$APP does not provide, host, or distribute any media streams. All streams are sourced from user-configured third-party add-ons.",
        "This application is intended for use with content you own or have the legal right to access. Users are responsible for ensuring their use complies with applicable laws.",
        "All third-party service, product, codec and format names, logos and trademarks displayed by Ember belong to their respective owners. Ember claims no ownership of these marks and uses them only for identification. Their display does not imply affiliation, sponsorship or endorsement.",
        "$APP is provided \"as is\", without warranty of any kind.",
    )
}
