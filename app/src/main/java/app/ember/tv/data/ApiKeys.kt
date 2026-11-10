package app.ember.tv.data

import android.content.Context
import app.ember.tv.BuildConfig
import app.ember.tv.tracker.TokenStore
import kotlin.concurrent.thread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf

/**
 * API keys for the services that need one. A key entered in Settings > API keys wins over the one
 * built into the APK (from local.properties or a GitHub secret), so the app works without
 * rebuilding. Entered keys live in the encrypted TokenStore and travel with backups.
 */
// Holds the application context only (set once from EmberApp), never an Activity.
@android.annotation.SuppressLint("StaticFieldLeak")
object ApiKeys {
    data class Field(val id: String, val label: String, val optional: Boolean = false)

    data class Service(
        val name: String,
        val usedFor: String,
        val where: String,
        val fields: List<Field>,
    )

    val SERVICES = listOf(
        Service(
            "Streaming Availability",
            "Optional Where to watch provider alongside TMDB and Watchmode. Select it in Streaming services settings.",
            "rapidapi.com/movie-of-the-night/api/streaming-availability: subscribe to a plan and copy X-RapidAPI-Key.",
            listOf(Field("STREAMING_AVAILABILITY_API_KEY", "RapidAPI key")),
        ),
        Service(
            "Mistral AI",
            "Optional AI-assisted Discover: turns your description into filters you review. Disabled by default; manual discovery works without it.",
            "console.mistral.ai > API keys. Your plan may charge for requests. Enable AI-assisted discovery in Discover settings.",
            listOf(Field("MISTRAL_API_KEY", "API key")),
        ),
        Service(
            "TMDB",
            "Hero backgrounds and title logos, tracker posters and discovery.",
            "themoviedb.org > Settings > API. Use the \"API Key\" (v3), not the read token.",
            listOf(Field("TMDB_API_KEY", "API key")),
        ),
        Service(
            "Fanart.tv",
            "Optional hero backgrounds and HD title logos. Existing artwork remains available without a key.",
            "fanart.tv > API > create a personal API key.",
            listOf(Field("FANART_API_KEY", "API key")),
        ),
        Service(
            "Trakt",
            "Sign-in, Continue watching, watchlist, Recommended and your lists.",
            "trakt.tv/oauth/applications > New application. Redirect URI: urn:ietf:wg:oauth:2.0:oob",
            listOf(Field("TRAKT_CLIENT_ID", "Client ID"), Field("TRAKT_CLIENT_SECRET", "Client secret")),
        ),
        Service(
            "WeTrakr",
            "Sign-in, progress and watchlist.",
            "Your WeTrakr developer settings > new application.",
            listOf(Field("WETRAKR_CLIENT_ID", "Client ID"), Field("WETRAKR_CLIENT_SECRET", "Client secret")),
        ),
        Service(
            "Simkl",
            "Sign-in, progress and watchlist.",
            "simkl.com/settings/developer > new app.",
            listOf(Field("SIMKL_CLIENT_ID", "Client ID")),
        ),
        Service(
            "AniList",
            "Optional: lets Ember update your AniList progress when you finish an episode. Your list reads by username without it.",
            "anilist.co/settings/developer > Create client, redirect URL https://anilist.co/api/v2/oauth/pin. Then open anilist.co/api/v2/oauth/authorize?client_id=YOUR_ID&response_type=token on your phone, approve, and paste the token here (easiest from the phone page).",
            listOf(Field("ANILIST_TOKEN", "Access token")),
        ),
        Service(
            "MyAnimeList",
            "Reading your anime list by username.",
            "myanimelist.net/apiconfig > Create ID. App type: other.",
            listOf(Field("MAL_CLIENT_ID", "Client ID")),
        ),
        Service(
            "RPDB",
            "Posters with ratings (Settings > Posters). Without a key the free one is used, which only has the default style.",
            "ratingposterdb.com: the key arrives by email after subscribing on Patreon. Keys start with t1- to t4-.",
            listOf(Field("RPDB_API_KEY", "API key")),
        ),
        Service(
            "MDBList",
            "Ratings: IMDb, TMDB, Trakt, Metacritic and Rotten Tomatoes in one request (Settings > Ratings). Free: 1,000 a day.",
            "mdblist.com > sign in > Preferences > API key.",
            listOf(Field("MDBLIST_API_KEY", "API key")),
        ),
        Service(
            "OMDb",
            "Ratings: IMDb, Metacritic and Rotten Tomatoes, used when there is no MDBList key. Free: 1,000 a day.",
            "omdbapi.com > API Key > Free; the key arrives by email.",
            listOf(Field("OMDB_API_KEY", "API key")),
        ),
        Service(
            "TVDB",
            "TV show details, artwork and episode names (Settings > Artwork & metadata).",
            "thetvdb.com > Dashboard > API keys: create a project key. The PIN is only for keys tied to a TVDB subscription.",
            listOf(Field("TVDB_API_KEY", "API key"), Field("TVDB_PIN", "Subscriber PIN (optional)", optional = true)),
        ),
        Service(
            "OpenSubtitles.com",
            "Subtitles (Settings > Subtitles): ranking by file hash, release, frame rate and trust. Without a login the daily download limit is low.",
            "opensubtitles.com > sign in > API consumers > New consumer: copy the API key. Your site username and password are optional.",
            listOf(
                Field("OPENSUBTITLES_API_KEY", "API key"),
                Field("OPENSUBTITLES_USERNAME", "Username (optional)", optional = true),
                Field("OPENSUBTITLES_PASSWORD", "Password (optional)", optional = true),
            ),
        ),
        Service(
            "SubDL",
            "Subtitles (Settings > Subtitles), with release names for matching.",
            "subdl.com > sign in > Account > API key.",
            listOf(Field("SUBDL_API_KEY", "API key")),
        ),
        Service(
            "Watchmode",
            "Where to watch on title screens. Free plan: about 1,000 requests a month.",
            "api.watchmode.com > sign up; the key is on your account page.",
            listOf(Field("WATCHMODE_API_KEY", "API key")),
        ),
    )

    val ALL_IDS: List<String> = SERVICES.flatMap { s -> s.fields.map { it.id } }

    private const val PREFIX = "apikey."
    private var app: Context? = null
    var revision by mutableIntStateOf(0); private set
    @Volatile private var cache: Map<String, String>? = null

    /** Called once from EmberApp. Loads entered keys off the main thread (the keystore is slow). */
    fun init(context: Context) {
        app = context.applicationContext
        thread(name = "ember-keys") { entered() }
    }

    private fun entered(): Map<String, String> {
        cache?.let { return it }
        val ctx = app ?: return emptyMap()
        return synchronized(this) {
            cache ?: TokenStore(ctx).let { s ->
                ALL_IDS.mapNotNull { id -> s.get(PREFIX + id)?.trim()?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
            }.also { cache = it }
        }
    }

    /** The key in use: entered on this TV, otherwise built in, otherwise blank. */
    fun get(id: String): String = entered()[id] ?: builtIn(id)

    fun builtIn(id: String): String = when (id) {
        "TMDB_API_KEY" -> BuildConfig.TMDB_API_KEY
        "FANART_API_KEY" -> BuildConfig.FANART_API_KEY
        "TRAKT_CLIENT_ID" -> BuildConfig.TRAKT_CLIENT_ID
        "TRAKT_CLIENT_SECRET" -> BuildConfig.TRAKT_CLIENT_SECRET
        "WETRAKR_CLIENT_ID" -> BuildConfig.WETRAKR_CLIENT_ID
        "WETRAKR_CLIENT_SECRET" -> BuildConfig.WETRAKR_CLIENT_SECRET
        "SIMKL_CLIENT_ID" -> BuildConfig.SIMKL_CLIENT_ID
        "MAL_CLIENT_ID" -> BuildConfig.MAL_CLIENT_ID
        "WATCHMODE_API_KEY" -> BuildConfig.WATCHMODE_API_KEY
        "STREAMING_AVAILABILITY_API_KEY" -> BuildConfig.STREAMING_AVAILABILITY_API_KEY
        "MISTRAL_API_KEY" -> BuildConfig.MISTRAL_API_KEY
        else -> ""
    }

    fun isEntered(id: String): Boolean = entered().containsKey(id)

    /** Saves [value] for [id]; a blank value removes the entered key, falling back to the built-in one. */
    fun set(id: String, value: String) {
        val ctx = app ?: return
        val v = value.trim()
        val store = TokenStore(ctx)
        if (v.isEmpty()) store.remove(PREFIX + id) else store.put(PREFIX + id, v)
        synchronized(this) {
            cache = entered().toMutableMap().apply { if (v.isEmpty()) remove(id) else put(id, v) }
            revision++
        }
    }

    /** Last four characters, for showing which key is saved without showing the key. */
    fun hint(value: String): String = if (value.length <= 4) "••••" else "••••" + value.takeLast(4)

    /** Forget the in-memory copy, e.g. after a restore wrote new keys. */
    fun reload() {
        synchronized(this) { cache = null; revision++ }
    }
}
