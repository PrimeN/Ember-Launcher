<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="tools/brand/png/ember-logo-horizontal-on-dark.png">
    <img src="tools/brand/png/ember-logo-horizontal-on-light.png" alt="Ember TV" width="360">
  </picture>
</p>

# Ember TV

**Your apps. Your media. Your home screen.**

**Version 0.9.5.1** · Android 10+ · ARM64 and ARMv7

Ember is an ad-free Home screen replacement for **Android TV and Google TV**. It brings your apps, movies, shows, personal media libraries and live channels into one customizable interface built for a TV remote.

Use it as your everyday launcher, connect the sources you want, and choose how your TV looks and works.

[Features](#features) · [Integrations and API keys](#built-in-integrations-and-api-keys) · [Getting started](#getting-started) · [Build from source](#build-from-source) · [Documentation](#documentation)

## Features

### A Home screen that fits you

- **Three interfaces:** choose Modern, Light or Ember X, with shared access to your media and settings.
- **Independent media pages:** choose the current Modern layout, Immersive Glass or Ember X with its episode/source side panel, regardless of your Home interface or player controls.
- **Customizable navigation and rows:** choose visible tabs, arrange your Home content, and enable the sections you use.
- **Personal app layouts:** reorder or hide apps, create folders, customize artwork and protect app launches with a PIN.
- **Unified App Library:** browse installed apps in a responsive artwork grid, search by name, and switch between custom or alphabetical order without changing your saved layout.
- **Featured media:** artwork, title logos and configurable hero carousels, with watchlist shortcuts and selectable content sources.
- **Appearance controls:** colour themes, day/night modes, text and interface sizing, focus effects and glass-style controls.

| Interface | Experience |
|---|---|
| **Modern** | A media-focused Home screen with featured artwork, configurable content rows and compact navigation. |
| **Light** | An app-focused layout with a six-column grid, dock, background choices and a carousel that can expand when idle. |
| **Ember X** | A cinema-style layout with prominent artwork, detailed media pages and structured playback-source information. |

Light can also show participating apps' Android TV Watch Next entries and recommendations when the required access is available.

### Discover movies, shows and collections

- **Detailed filtering:** browse movies, shows or a mix; filter by genre, release year, rating, streaming service, actor, director and production company.
- **Awards and sorting:** explore Oscar winners or nominees, including Best Picture filters, and sort by popularity, rating or release date.
- **Ranked selections:** explore up to 100 matching titles and save Discover searches as collection sources.
- **Configurable sections:** show or hide Explore, Collections and Catalogs in Discover.
- **Search:** find media through your configured sources using text or voice input, with a responsive results grid, compact filters, source labels and optional duplicate combining. Light also provides quick search for apps, settings and available TV inputs.
- **Optional AI assistance:** describe what you want to watch and review suggested filters using your own Mistral configuration.

### Add-ons and catalogs

Ember supports **Stremio-compatible add-ons** for catalogs, title information, playback sources and subtitles.

- Enable, disable, rename and reorder individual catalogs.
- Create, import and organize collections with folders and multiple content sources.
- Use optional animated collection covers and previews.
- Customize source formatting and badges for resolution, quality, languages, audio, video formats and other file details.
- Choose which catalog and collection content appears on Home and in Discover.

### Your libraries and watchlists, together

Connect **Plex, Jellyfin or Emby** to browse your own media alongside add-on content.

- Library rows for movies, shows, recently added titles and Continue watching.
- Configurable library sections and an optional server-fed hero carousel.
- Server copies included in a title's available playback sources.
- Resume playback and report progress to supported connected services.
- Select server users, including Plex Home profiles, where supported by the server.
- Combine local lists, server content and tracker lists, with controls over which sources appear.
- View cast and crew, explore their other work, and open related titles.
- Display available ratings from IMDb, TMDB, Trakt, Metacritic, Rotten Tomatoes and AniList through configured metadata and rating sources.
- Show watched indicators and optionally blur unwatched episode artwork to reduce spoilers.

### Live TV and IPTV

Bring your own **M3U playlists or Xtream Codes sources**, with **XMLTV** programme guides. Glass Guide is the default Live view; switch to On Now Canvas on screen to browse current programmes by artwork. Your selected view is saved.

- Browse channels by playlist, group, favourites or custom channel lists.
- See current programmes, schedules, progress and remaining time when guide data is available.
- Hide unused channels and groups to keep navigation focused.
- Manage multiple sources and choose which content appears in the interface.

Ember does not include an IPTV subscription or a preloaded channel package.

### Built-in playback

Ember includes **Standard playback through AndroidX Media3** and an internal **Compatibility engine using libmpv**. Automatic recovery can switch routes for supported playback failures, while both engines use Ember's playback controls and resume state.

- **Playback controls:** resume, seeking, playback speed, queues, repeat options, next-episode playback and picture-in-picture.
- **Audio and video:** hardware-first decoding, optional software fallback, supported HDMI audio passthrough, frame-rate matching and playback information.
- **Subtitles:** embedded tracks, online providers and local subtitle files, with language selection, timing adjustment and appearance controls.
- **Intro skipping:** supported server markers and an optional IntroDB fallback for available intro, recap and credits timings.
- **Player appearance:** select a control layout independently of the Home interface. Modern uses a floating Ember Glass dock; Basic/Simple uses the minimal Cinema overlay. Both share track selection, playback settings and remote controls.
- **Local and network media:** open files through Android's picker or configured SMB, WebDAV, SFTP, FTP and FTPS connections.
- **External-player choice:** hand playback to a compatible installed player when preferred.

Codec, 4K, HDR, Dolby Vision and surround-audio capabilities depend on the media, playback engine, device, display and audio equipment. See [player dependencies and playback details](player-dependencies.md) for the technical capabilities and limitations.

### Profiles, privacy and performance

- **Personal profiles:** separate Home layouts and personal lists, with quick profile switching.
- **Child profiles:** app and catalog restrictions, genre and age-rating filters, and administrator PIN controls within Ember.
- **Device performance controls:** Auto, Low, Medium, High, Ultra and Custom options for artwork quality, motion, glass effects, analysis and prefetch behaviour. Ultra is available on supported 4K displays.
- **Artwork caching:** reuses downloaded images and small prepared glass thumbnails across launches. Private server artwork is kept separate per profile and account. Set image retention, view usage or clear disposable files in System > Cache Management.
- **Phone setup:** an opt-in local setup page for easier text entry and configuration, protected by a temporary code and a 15-minute session limit.
- **Backups:** export and restore settings and supported account configuration, with optional passphrase encryption.
- **Credential storage:** entered sign-ins and API keys use encrypted storage on the device.

Backups can contain credentials. Use passphrase encryption when exporting sensitive configuration, and enable Phone setup only on a trusted local network.

### Everyday TV tools

- Weather with location, unit and provider choices.
- Screensavers using configured artwork, photos and supported online image sources.
- Shortcuts to TV inputs and supported device settings.
- Display calibration patterns, HDR test clips and detected firmware-menu shortcuts.
- Optional startup destinations, photo/video presentations and Now Playing controls for supported apps.
- Searchable settings and a compact quick-settings menu.
- In-app update channels and diagnostic tools.

Input switching, Home-button integration and manufacturer-specific menus depend on the permissions and features exposed by the device's firmware.

## Built-in integrations and API keys

The integrations below are built into Ember. Enable the ones you want; the launcher, app management, built-in players and local media playback do not require a personal API key.

**An included integration does not include a personal account, paid subscription or API entitlement.** Where a key is required, enter your own in **Settings → API keys** unless the APK publisher has already supplied a usable default. Builds can supply defaults for selected providers and tracker app registrations; availability varies by build. Keys entered on the TV override built-in defaults.

### Metadata, artwork and ratings

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
| **Cinemeta** | Public movie/show results in Light search. | No account or API key. |

### Media servers and watch tracking

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **Plex** | Your server libraries, playback sources and progress. | Your Plex account sign-in; no personal developer API key. [Plex account](https://www.plex.tv/sign-up/). |
| **Jellyfin / Emby** | Your server libraries, playback sources and progress. | Your server address and user sign-in; no separate personal developer API key. Obtain these from your server administrator or set up [Jellyfin](https://jellyfin.org/) / [Emby](https://emby.media/). |
| **Trakt** | Watchlists, progress, recommendations, lists and supported scrobbling. | Account authorization with a code. The build must provide an app registration, or you must enter a client ID and client secret. [Register an app](https://trakt.tv/oauth/applications). |
| **WeTrakr** | Watchlists and viewing progress. | Account authorization with a code. The build must provide an app registration, or you must enter a client ID and client secret through your [WeTrakr account](https://wetrakr.com/). |
| **Simkl** | Supported viewing progress and watchlist integration. | Account authorization with a code. An app client ID must be supplied by the build or entered in Settings. [Developer apps](https://simkl.com/settings/developer/). |
| **MyAnimeList** | Read public anime lists. | A username and app client ID; supply the client ID if the build does not include one. [Create a client ID](https://myanimelist.net/apiconfig). |

Tracker app registrations identify Ember to a service; they are separate from your personal account authorization. Available reading, syncing and scrobbling features vary by provider.

### Streaming availability, subtitles and discovery

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **Watchmode** | Regional streaming availability on media pages. | Your API key, unless supplied by the build. [API registration](https://api.watchmode.com/). |
| **Streaming Availability** | An alternative regional availability provider through RapidAPI. | Your RapidAPI key and a provider plan. [Provider API and access information](https://www.movieofthenight.com/about/api). |
| **OpenSubtitles.com** | Subtitle search and downloads, including file-matching information. | Your API key. Account username/password are optional and affect provider download access and limits. [Register an API consumer](https://www.opensubtitles.com/consumers). |
| **SubDL** | Subtitle search and downloads. | Your personal API key. [API key panel](https://subdl.com/panel/api). |
| **IntroDB** | Optional intro, recap and credits timings when server markers are unavailable. | No account or API key. Enable the fallback in player settings. |
| **Mistral AI** | Optional suggestions that turn a description into Discover filters for review. | Your API key and provider API access; usage may incur charges. Disabled until enabled. [API key console](https://console.mistral.ai/api-keys). |
| **YouTube trailers** | Trailers through the YouTube app or experimental in-app playback. | No personal YouTube API key. |

### Weather and utilities

| Built-in service | What it provides | Credentials required |
|---|---|---|
| **Open-Meteo / MET Norway / wttr.in** | Weather forecasts with provider selection and fallback. | No personal API key for the integrated public endpoints. |
| **ipapi.co** | Approximate location when you choose to look it up. | No personal API key for the integrated lookup. |
| **Cleveland Museum of Art / NASA Image and Video Library** | Optional artwork and space-image screensaver sources. | No personal API key. |
| **GitHub updates** | Standard updates use public [Ember releases](https://github.com/PrimeN/Ember-Launcher/releases). Beta can choose the public [beta release](https://github.com/PrimeN/Ember-Launcher/releases/tag/beta-build) or a successful signed Actions APK. Actions downloads use [nightly.link](https://nightly.link) and verify GitHub's archive checksum. | No GitHub account, API key or token for either source. Actions artifacts expire after three days. |

Add-ons, IPTV providers and network shares may require their own subscription, login, key or connection details. Those requirements belong to the source you configure. External services apply their own terms, availability limits and privacy policies.

## Getting started

1. **Install Ember** on a compatible Android TV or Google TV device. See [Building Ember TV](BUILDING.md) for source-build and installation instructions.
2. **Choose your Home interface** and adjust the visible tabs, app layout and theme.
3. **Set Ember as your Home app** where supported. Home-button options are available in Settings for compatible devices.
4. **Connect optional sources:** add a media server, install add-ons, import an IPTV playlist or connect a tracker.
5. **Make it yours:** choose Home rows, configure Discover and create any additional profiles you need.

Provider credentials can be entered in **Settings → API keys**. Phone setup offers an optional way to enter supported configuration from another device.

## Requirements

| Requirement | Supported configuration |
|---|---|
| **Operating system** | Android TV or Google TV running Android 10 or newer. |
| **CPU architecture** | ARM64 (`arm64-v8a`) or ARMv7 (`armeabi-v7a`). |
| **Controls** | A TV remote with D-pad, OK and Back controls. |
| **Network access** | Required for online sources, connected services, updates and remote libraries. |

Device capabilities determine which playback formats, system shortcuts and launcher-integration options are available.

## Build from source

Use **JDK 17**, **Gradle 9.8.2**, **Android SDK platform 37** and **Python 3**. Full setup, optional credentials, signing and GitHub Actions instructions are in [BUILDING.md](BUILDING.md).

Run the JVM tests and Android lint:

```sh
gradle :app:testDebugUnitTest :app:lintDebug
```

Build a debug APK:

```sh
gradle :app:assembleDebug
```

Development takes place on `beta`; `main` contains the release branch. Device testing remains necessary for remote navigation, rendering, firmware integration and hardware playback.

## Documentation

| Guide | Contents |
|---|---|
| [Build and installation](BUILDING.md) | Development setup, optional credentials, signing and CI builds. |
| [Device performance](device-performance.md) | Rendering profiles, artwork budgets and performance measurement. |
| [Phone setup](phone-setup-security.md) | Session controls, authentication, backups and local-network behaviour. |
| [Playback dependencies](player-dependencies.md) | Playback architecture, native components, source references and verification. |
| [Performance testing](performance/README.md) | Device benchmarks and startup-profile generation. |
| [MPEG-H decoder](app/native/mpegh/README.md) | Decoder sources, license information and rebuild instructions. |
| [Native FFmpeg filters](app/native/ffmpeg-filters/README.md) | Filter configuration, source references and native verification. |

## Feedback

Report reproducible problems through [GitHub Issues](https://github.com/PrimeN/Ember-Launcher/issues). Include your device, Android version, Ember version, selected interface and steps to reproduce. For playback issues, include the engine and relevant media formats. Remove credentials and private media URLs from any logs you share.

## License and third-party notices

Ember's project code is licensed under the **[GNU General Public License v3](LICENSE)**. Third-party components and assets retain their applicable licenses and terms.

Copyright notices, service information and third-party license texts are available in **Settings → Legal** and in the [bundled legal notices](app/src/main/assets/licenses/). Native components have their own licensing and corresponding-source requirements; consult the playback and native build documentation before redistributing binaries.

Ember is not affiliated with or endorsed by the services or device manufacturers it integrates.
