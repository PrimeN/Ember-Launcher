# Built-in integrations and API keys

The integrations below are built into Ember. Enable the ones you want; the launcher, app management, built-in players and local media playback do not require a personal API key.

**An included integration does not include a personal account, paid subscription or API entitlement.** Where a key is required, enter your own in **Settings › Accounts › API keys** unless the APK publisher has already supplied a usable default. Builds can supply defaults for selected providers and tracker app registrations; availability varies by build. Keys entered on the TV override built-in defaults.

## Metadata, artwork and ratings

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **TMDB** | Movie/show information, Discover, posters, hero artwork and regional streaming availability. | TMDB v3 API key; supply your own if the build does not include one. [Get a key](https://www.themoviedb.org/settings/api). |
| **Fanart.tv** | Alternative backgrounds and high-resolution title logos. | Personal API key; supply your own if the build does not include one. [Get a key](https://fanart.tv/get-an-api-key/). |
| **TVDB** | TV show information, artwork and episode names. | Your API key; a subscriber PIN is needed only when required by that key. [API access](https://thetvdb.com/api-information). |
| **AniList** | Anime information, ratings and public anime lists. | No API key. A username is needed to read a public list. |
| **MDBList** | Ratings from multiple services in one lookup. | Your personal API key. [Account preferences](https://mdblist.com/preferences/). |
| **OMDb** | Additional IMDb, Metacritic and Rotten Tomatoes ratings. | Your personal API key. [Get a key](https://www.omdbapi.com/apikey.aspx). |
| **RPDB** | Posters with rating badges. | A built-in free key supports the default style. Use your own key and appropriate provider plan for additional styles. [RPDB plans and access](https://ratingposterdb.com/). |
| **BetterPosters** | Alternative poster artwork. | No personal API key for the public poster integration. |
| **Cinemeta** | Public movie/show results in Ember Light search. | No account or API key. |

## Media servers and watch tracking

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **Plex** | Your server libraries, playback sources and progress. | Your Plex account sign-in; no personal developer API key. [Plex account](https://www.plex.tv/sign-up/). |
| **Jellyfin / Emby** | Your server libraries, playback sources and progress. | Your server address and user sign-in; no separate personal developer API key. Obtain these from your server administrator or set up [Jellyfin](https://jellyfin.org/) / [Emby](https://emby.media/). |
| **Trakt** | Watchlists, progress, recommendations, lists and supported scrobbling. | Account authorization with a code. The build must provide an app registration, or you must enter a client ID and client secret. [Register an app](https://trakt.tv/oauth/applications). |
| **WeTrakr** | Watchlists and viewing progress. | Account authorization with a code. The build must provide an app registration, or you must enter a client ID and client secret through your [WeTrakr account](https://wetrakr.com/). |
| **Simkl** | Supported viewing progress and watchlist integration. | Account authorization with a code. An app client ID must be supplied by the build or entered in Settings. [Developer apps](https://simkl.com/settings/developer/). |
| **MyAnimeList** | Read public anime lists. | A username and app client ID; supply the client ID if the build does not include one. [Create a client ID](https://myanimelist.net/apiconfig). |

Tracker app registrations identify Ember to a service; they are separate from your personal account authorization. Available reading, syncing and scrobbling features vary by provider.

## Streaming availability, subtitles and discovery

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **Watchmode** | Regional streaming availability on media pages. | Your API key, unless supplied by the build. [API registration](https://api.watchmode.com/). |
| **Streaming Availability** | An alternative regional availability provider through RapidAPI. | Your RapidAPI key and a provider plan. [Provider API and access information](https://www.movieofthenight.com/about/api). |
| **OpenSubtitles.com** | Subtitle search and downloads, including file-matching information. | Your API key. Account username/password are optional and affect provider download access and limits. [Register an API consumer](https://www.opensubtitles.com/consumers). |
| **SubDL** | Subtitle search and downloads. | Your personal API key. [API key panel](https://subdl.com/panel/api). |
| **IntroDB** | Optional intro, recap and credits timings when server markers are unavailable. | No account or API key. Enable the fallback in player settings. |
| **Mistral AI** | Optional suggestions that turn a description into Discover filters for review. | Your API key and provider API access; usage may incur charges. Disabled until enabled. [API key console](https://console.mistral.ai/api-keys). |
| **YouTube trailers** | Trailers through the YouTube app or experimental in-app playback. | No personal YouTube API key. |

## Weather and utilities

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **Open-Meteo / MET Norway / wttr.in** | Weather forecasts with provider selection and fallback. | No personal API key for the integrated public endpoints. |
| **ipapi.co** | Approximate location when you choose to look it up. | No personal API key for the integrated lookup. |
| **Cleveland Museum of Art / NASA Image and Video Library** | Optional artwork and space-image screensaver sources. | No personal API key. |
| **GitHub updates** | Standard updates use public [Ember releases](https://github.com/PrimeN/Ember-Launcher/releases). Beta can choose the public [beta release](https://github.com/PrimeN/Ember-Launcher/releases/tag/beta-build) or a successful signed Actions APK. Actions downloads use [nightly.link](https://nightly.link) and verify GitHub's archive checksum. | No GitHub account, API key or token for either source. Actions artifacts expire after three days. |

Addons, IPTV providers and network shares may require their own subscription, login, key or connection details. Those requirements belong to the source you configure. External services apply their own terms, availability limits and privacy policies.


[Features](features.md) · [Build configuration](../BUILDING.md) · [Back to Ember](../README.md)
