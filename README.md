<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="tools/brand/png/ember-logo-horizontal-on-dark.png">
    <img src="tools/brand/png/ember-logo-horizontal-on-light.png" width="420" alt="Ember TV">
  </picture>
</p>

<h1 align="center">Your apps. Your media. Your home screen.</h1>

<p align="center">
  An ad-free Home screen replacement for Android TV and Google TV.<br>
  Bring your apps, libraries, catalogs and live channels together, then make it yours.
</p>

<p align="center">
  <a href="https://github.com/PrimeN/Ember-Launcher/actions/workflows/build.yml?query=branch%3Abeta"><img src="https://github.com/PrimeN/Ember-Launcher/actions/workflows/build.yml/badge.svg?branch=beta" alt="Android build and checks"></a>
  <a href="https://github.com/PrimeN/Ember-Launcher/releases/latest"><img src="https://img.shields.io/github/v/release/PrimeN/Ember-Launcher?color=ff9d48" alt="Latest stable release"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 10 or newer">
  <img src="https://img.shields.io/badge/ARM-32%20%26%2064%20bit-ff9d48" alt="ARM32 and ARM64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-ff9d48" alt="GNU GPL v3"></a>
</p>

<p align="center">
  <a href="https://github.com/PrimeN/Ember-Launcher/releases"><img src="https://img.shields.io/github/downloads/PrimeN/Ember-Launcher/total?color=ff9d48" alt="GitHub release asset downloads"></a>
  <a href="https://github.com/PrimeN/Ember-Launcher/stargazers"><img src="https://img.shields.io/github/stars/PrimeN/Ember-Launcher?color=ff9d48" alt="GitHub stars"></a>
  <a href="https://github.com/PrimeN/Ember-Launcher/issues"><img src="https://img.shields.io/github/issues/PrimeN/Ember-Launcher?color=ff9d48" alt="Open issues"></a>
  <a href="https://github.com/PrimeN/Ember-Launcher/commits/beta/"><img src="https://img.shields.io/github/last-commit/PrimeN/Ember-Launcher/beta?color=ff9d48" alt="Latest beta commit"></a>
</p>

<p align="center">
  <a href="https://github.com/PrimeN/Ember-Launcher/releases/latest">Latest release</a> ·
  <a href="#features">Features</a> ·
  <a href="docs/appearance.md">Appearance</a> ·
  <a href="docs/integrations.md">Integrations &amp; keys</a> ·
  <a href="docs/README.md">Guides</a> ·
  <a href="https://github.com/PrimeN/Ember-Launcher/issues/new?title=%5BBug%5D%20">Report a bug</a>
</p>

## Make the TV yours

Ember replaces the Home screen with a remote-friendly space for the content you
choose. Open installed apps, resume a show, browse your own server, explore a
catalog or check what's on now. Configure the rows, sources and profiles you use,
with artwork and controls that suit your TV.

Start with the media-focused **Ember** interface or the app-focused **Ember Light**.
Navigation, media pages, playback controls, colours and day/night modes are
separate choices. You can change the look without replacing your setup.

## Features

| | Included in Ember |
| --- | --- |
| **Your Home** | Configurable hero artwork, apps and folders, personal rows, watchlist shortcuts and Home widgets |
| **Home widgets** | Rows, numbered rows, cinematic carousels and hero banners using catalogs, collections or personal lists; optional app Continue watching and Recently used apps |
| **Search & Discover** | Text/voice search, compact results, detailed movie/show filters, awards, streaming availability, collections and catalogs |
| **Add-ons & collections** | Stremio-compatible catalogs, metadata, streams and subtitles; individually arranged catalogs and organized collection folders |
| **Personal libraries** | Plex, Jellyfin and Emby, server users where supported, Continue watching, configurable shelves and optional library heroes |
| **Live TV** | M3U and Xtream Codes sources, XMLTV guides, favourites and custom lists, with Glass Guide and On Now Canvas views |
| **Playback** | Built-in Media3 and libmpv engines, resume, subtitles, queues, intro markers and optional external players |
| **Profiles** | Personal layouts and lists, child-profile restrictions, PIN controls and watched/spoiler preferences |
| **TV tools** | Weather, screensavers, input shortcuts, calibration media and supported firmware settings |
| **Phone & backups** | Temporary phone setup, an optional paired phone remote, manual exports and encrypted automatic backups on the TV |
| **Performance** | Device-aware rendering, custom artwork/effects budgets, disk caching and cache management |

[Full feature guide →](docs/features.md)

## One app, different looks

| Choice | Options | What it changes |
| --- | --- | --- |
| **Home interface** | Ember · Ember Light | A media-focused Home or an app-focused layout |
| **Navbar** | Ember · Ember Collapsed · Cinema Bar | Navigation on the Ember interface; Light keeps its own app-tile navigation |
| **Media page** | Ember · Ember X | Artwork-led details or episode/source selection in a right-hand panel |
| **Player controls** | Ember · Glass · Basic · Streaming | Playback-control presentation, independent of the Home interface |
| **Colour palette** | Ember · Charcoal · Green · Rose · Android TV · Red | Interface surfaces, focus and accent colours |
| **Brightness mode** | Day · Night · Auto | Light/dark presentation, with an optional OLED-black preference |
| **Density & effects** | Interface/text size, card density, focus feedback and glass | How much fits on screen and how it responds |

**Ember Light does not mean a light colour theme.** It is a different Home layout
and supports its own day/night presentation. Likewise, Ember X is a media-page
choice, not another Home interface.

[Explore layouts, palettes and visual options →](docs/appearance.md)

## Get started

1. Download the APK from the [latest release](https://github.com/PrimeN/Ember-Launcher/releases/latest).
2. Install it on an **Android TV or Google TV device running Android 10+**, with
   an ARM64 or ARMv7 processor.
3. Choose Ember as the Home app where your firmware supports it. Configure the
   available Home-button options under **TV & remote** if needed.
4. Pick your interface, visible tabs and app layout. Then connect the optional
   sources you use: add-ons, a server, IPTV or a tracker.
5. Open **Appearance** for colours and layouts, **Widgets & collections** for Home
   content, and **Performance & effects** for your device's rendering budget.

Use a remote with D-pad, OK and Back. Launcher integration, inputs, engineering
menus and audio-output shortcuts depend on what the device firmware exposes.
Ember does not include an IPTV subscription or a preloaded streaming package.

## Built-in integrations

Ember includes connections for metadata, artwork, ratings, watch tracking, media
servers, subtitles and weather. Examples include **TMDB, Fanart.tv, TVDB, AniList,
MDBList, OMDb, Plex, Jellyfin, Emby, Trakt, WeTrakr, Simkl, OpenSubtitles, SubDL**
and **IntroDB**.

An included integration does not include a subscription or personal API entitlement.
Some services need your account or API key; the launcher and local app management
do not require a personal key. Builds may supply defaults for selected providers.
Enter your own configuration under **Settings → API keys** where required.

[Service-by-service requirements and credential links →](docs/integrations.md)

## Performance and privacy

Choose **Auto**, **Low**, **Medium**, **High**, **Ultra (4K)** on supported displays,
or **Custom**. Custom can combine better artwork with fewer effects. These profiles
set launcher-rendering budgets; they do not reduce the quality of a playing video.

Artwork and prepared glass thumbnails can be reused across launches. Use
**Storage & diagnostics → Cache management** for retention and cleanup. Private
server artwork is separated by profile and account; entered sign-ins and keys use
encrypted storage.

Phone setup is opt-in and has a temporary code and 15-minute session limit. The
paired phone remote is a separate feature: it is off by default and stays enabled
until you turn it off. Use these tools on a trusted local network.

Automatic backups are encrypted and kept on the same TV. They are not a cloud
backup and do not survive uninstalling Ember. Make a separate passphrase-encrypted
export when moving devices or keeping a backup elsewhere.

[Performance guide](device-performance.md) · [Phone setup and security](phone-setup-security.md)

## Build and updates

Development takes place on **beta**; **main** is the release branch. For a source
build, install **JDK 17**, **Gradle 9.8.2**, **Android SDK platform 37** and **Python 3**:

```sh
git clone --branch beta https://github.com/PrimeN/Ember-Launcher.git
cd Ember-Launcher
gradle :app:testDebugUnitTest :app:lintDebug
gradle :app:assembleDebug
```

The repository does not include a runnable Gradle wrapper. Normal builds use the
pinned native components; rebuilding those components requires their documented
toolchains. Keep local configuration and signing files out of source control.

Standard in-app updates use the public releases. Beta can choose the configured
beta-release or signed Actions source. Ordinary code pushes validate the project
without requesting a new public release.

[Build, installation, signing and update details →](BUILDING.md)

## Help and contribute

[Report a bug](https://github.com/PrimeN/Ember-Launcher/issues/new?title=%5BBug%5D%20)
with your device model, Android version, Ember version, selected interface and
steps to reproduce it. For playback problems, include the engine and relevant
formats. Remove passwords, API keys and private media URLs from attachments.

[Suggest a feature](https://github.com/PrimeN/Ember-Launcher/issues/new?title=%5BFeature%5D%20)
with a concrete example of what you want to do. For a code contribution, open a
focused pull request against `beta` and link the relevant issue.

[Browse all guides →](docs/README.md)

## License

Ember's project code is licensed under the [GNU GPL v3](LICENSE). Third-party
components and assets retain their applicable licenses and terms. Notices are
available in **Settings → Legal** and the [bundled license directory](app/src/main/assets/licenses/).

[Playback dependencies](player-dependencies.md) and the native build guides
document component sources, modifications and rebuild requirements. Ember is not
affiliated with or endorsed by integrated services or device manufacturers.
