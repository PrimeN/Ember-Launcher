# Features

## A Home screen that fits you

- **Two interfaces:** choose Ember (default) or Ember Light, with shared access to your media and settings.
- **Independent media pages:** choose Ember or Ember X with its episode/source side panel, regardless of your Home interface or player controls.
- **Independent navigation:** Ember uses the Ember navbar by default, with Ember Collapsed and Cinema Bar alternatives. Ember Light puts navigation destinations in its app grid, with the same move and hide controls as apps.
- **Customizable navigation and rows:** choose visible tabs, arrange your Home content, and enable the sections you use.
- **Personal app layouts:** reorder or hide apps, create folders, customize artwork and protect app launches with a PIN.
- **Unified App Library:** browse installed apps in a responsive artwork grid, search by name, and switch between custom or alphabetical order without changing your saved layout.
- **Featured media:** artwork, title logos and configurable hero carousels, with watchlist shortcuts and selectable content sources.
- **Appearance controls:** colour themes, day/night modes, text and interface sizing, focus effects and glass-style controls.

| Interface | Experience |
|---|---|
| **Ember** | A media-focused Home screen with featured artwork, configurable content rows and compact navigation. |
| **Ember Light** | An app-focused layout with a six-column grid, dock, background choices and a carousel that can expand when idle. |

Ember Light can also show participating apps' Android TV Watch Next entries and recommendations when the required access is available.

## Discover movies, shows and collections

- **Detailed filtering:** browse movies, shows or a mix; filter by genre, release year, rating, streaming service, actor, director and production company.
- **Awards and sorting:** explore supported film and television awards, including Oscars, Golden Globes, BAFTA, festival awards and Emmys, and sort by popularity, rating or release date.
- **Ranked selections:** explore up to 100 matching titles and save Discover searches as collection sources.
- **Configurable sections:** show or hide Explore, Collections and Catalogs in Discover.
- **Search:** find media through your configured sources using text or voice input, with a responsive results grid, compact filters, source labels and optional duplicate combining. Ember Light also provides quick search for apps, settings and available TV inputs.
- **Optional AI assistance:** describe what you want to watch and review suggested filters using your own Mistral configuration.

## Addons and catalogs

Ember supports **Stremio-compatible addons** for catalogs, title information, playback sources and subtitles.

- Enable, disable, rename and reorder individual catalogs.
- Create, import and organize collections with folders and multiple content sources.
- Use optional animated collection covers and previews.
- Customize source formatting and badges for resolution, quality, languages, audio, video formats and other file details.
- Choose which catalog and collection content appears on Home and in Discover.

## Your libraries and watchlists, together

Connect **Plex, Jellyfin or Emby** to browse your own media alongside addon content.

- Library rows for movies, shows, recently added titles and Continue watching.
- Configurable library sections and an optional server-fed hero carousel.
- Server copies included in a title's available playback sources.
- Resume playback and report progress to supported connected services.
- Select server users, including Plex Home profiles, where supported by the server.
- Combine local lists, server content and tracker lists, with controls over which sources appear.
- View cast and crew, explore their other work, and open related titles.
- Display available ratings from IMDb, TMDB, Trakt, Metacritic, Rotten Tomatoes and AniList through configured metadata and rating sources.
- Show watched indicators and optionally blur unwatched episode artwork to reduce spoilers.

## Live TV and IPTV

Bring your own **M3U playlists or Xtream Codes sources**, with **XMLTV** programme guides. Glass Guide is the default Live view; switch to On Now Canvas on screen to browse current programmes by artwork. Your selected view is saved.

- Browse channels by playlist, group, favourites or custom channel lists.
- See current programmes, schedules, progress and remaining time when guide data is available.
- Hide unused channels and groups to keep navigation focused.
- Manage multiple sources and choose which content appears in the interface.

Ember does not include an IPTV subscription or a preloaded channel package.

## Built-in playback

Ember includes **Standard playback through AndroidX Media3** and an internal **Compatibility engine using libmpv**. Automatic recovery can switch routes for supported playback failures, while both engines use Ember's playback controls and resume state.

- **Playback controls:** resume, seeking, playback speed, queues, repeat options, next-episode playback and picture-in-picture.
- **Audio and video:** hardware-first decoding, optional software fallback, supported HDMI audio passthrough, frame-rate matching and playback information.
- **Subtitles:** embedded tracks, online providers and local subtitle files, with language selection, timing adjustment and appearance controls.
- **Intro skipping:** supported server markers and an optional IntroDB fallback for available intro, recap and credits timings.
- **Player preferences:** separate local/network decoder hints and format rules, optional fast keyframe seeking, and independent Bluetooth lip-sync timing.
- **Decoded-audio effects:** equalizer presets, bass adjustment, mono and front-channel swapping, with HDMI passthrough preserved.
- **Quick controls:** choose and reorder player shortcuts in Settings › Playback › Player or the player's quick menu.
- **Player appearance:** select a control layout independently of the Home interface. Choose Ember, Glass, Basic or Streaming controls. Track selection, playback settings and remote controls remain available independently of Home layout.
- **Local and network media:** open files through Android's picker or configured SMB, WebDAV, SFTP, FTP and FTPS connections.
- **External-player choice:** hand playback to a compatible installed player when preferred.

Codec, 4K, HDR, Dolby Vision and surround-audio capabilities depend on the media, playback engine, device, display and audio equipment. See [player dependencies and playback details](../player-dependencies.md) for the technical capabilities and limitations.

## Profiles, privacy and performance

- **Personal profiles:** separate Home layouts and personal lists, with quick profile switching.
- **Child profiles:** app and catalog restrictions, genre and age-rating filters, and administrator PIN controls within Ember.
- **Device performance controls:** Auto, Low, Medium, High, Ultra and Custom options for artwork quality, motion, glass effects, analysis and prefetch behaviour. Ultra is available on supported 4K displays.
- **Artwork caching:** reuses downloaded images and small prepared glass thumbnails across launches. Private server artwork is kept separate per profile and account. Set image retention, view usage or clear disposable files in System > Storage & diagnostics > Cache management.
- **Phone setup:** an opt-in local setup page for easier text entry and configuration, protected by a temporary code and a 15-minute session limit.
- **Backups:** export and restore settings and supported account configuration, with optional passphrase encryption.
- **Credential storage:** entered sign-ins and API keys use encrypted storage on the device.

Backups can contain credentials. Use passphrase encryption when exporting sensitive configuration, and enable Phone setup only on a trusted local network.

## Everyday TV tools

- Weather with location, unit and provider choices.
- Screensavers using configured artwork, photos and supported online image sources.
- Shortcuts to TV inputs and supported device settings.
- Display calibration patterns, HDR test clips and detected firmware-menu shortcuts.
- Optional startup destinations, photo/video presentations and Now Playing controls for supported apps.
- Searchable settings and a compact quick-settings menu.
- In-app update channels and diagnostic tools.

Input switching, Home-button integration and manufacturer-specific menus depend on the permissions and features exposed by the device's firmware.


## Home widgets and local tools

Create rows, numbered rows, cinematic carousels or hero-banner widgets. Sources
can include addon catalogs, supported external lists, saved Discover filters,
collections and personal lists. A widget can combine multiple catalog sources.
Widget packs can be exported and imported.

Optional device widgets show Continue watching from participating apps or apps
recently launched through Ember. The former requires Android access and content
provided by those apps; Recently used apps records launches through Ember.

Phone setup and the phone remote are separate tools. Setup is a temporary
15-minute configuration session. The optional paired phone remote is off by
default and remains on until switched off. Its settings are separate from
normal backups.

Automatic backups can run daily or weekly, or be turned off. They keep the newest
three encrypted backups privately on the same TV. Their encryption key belongs
to that installation, so they cannot replace a portable passphrase-encrypted
export and are lost when Ember is uninstalled.

[Appearance and layouts](appearance.md) · [Integrations](integrations.md) · [Back to Ember](../README.md)
