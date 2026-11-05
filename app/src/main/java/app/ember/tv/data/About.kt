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
                "Posters with ratings from RPDB or BetterPosters, each with its own options and a live preview.",
                "Ratings on title screens from IMDb, Trakt, TMDB, Metacritic, Rotten Tomatoes and AniList, in the order you choose.",
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
