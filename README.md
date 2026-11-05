# Ember TV

Ember is an ad-free Home screen replacement for Android TV and Google TV.
It brings installed apps, personal watchlists, media servers, add-on catalogs and
live TV together in an interface designed for a remote control.

Android 10 or newer is required. Builds target **arm64-v8a and armeabi-v7a**.

## Features

- **Home and apps:** configurable rows, app folders and artwork, per-profile layouts,
  featured media, Continue watching and optional Android TV Watch Next integration.
- **Interfaces:** Modern, Light and Ember X, with shared colour themes, accessibility
  settings and performance controls.
- **Discover:** movie/show filters, catalogs, collections, saved searches and optional
  regional streaming availability. Integrations that require API keys are optional.
- **Personal media:** Plex, Jellyfin and Emby accounts, native libraries, playback
  progress, collections and supported network folders.
- **Live TV:** user-supplied IPTV playlists, XMLTV guides, favourites, custom channel
  lists and per-source visibility controls. No IPTV subscription is included.
- **Playback:** built-in Standard and Compatibility engines, source selection,
  resume, audio/subtitle controls and optional external-player handoff. Format,
  HDR and audio-output support depends on the stream, engine and device.
- **Profiles:** separate preferences and personal lists, child-content filters,
  administrator PIN controls and app locks.
- **Device tools:** weather, screensavers, exposed TV inputs, supported system
  shortcuts and optional local presentation playlists.
- **Setup and backup:** encrypted sign-in/key storage, opt-in phone setup sessions,
  and backup/restore with an optional passphrase. Store exported backups privately.

Ember uses the sources and services you configure. Service subscriptions, playback
rights and provider API access are separate from the launcher.

## Build and test

See [BUILDING.md](BUILDING.md) for local setup, optional credentials, signing and
GitHub Actions. Work is developed on `beta`; `main` is the stable branch.

```sh
gradle :app:testDebugUnitTest :app:lintDebug
```

These checks compile and validate the app without assembling an APK. JVM tests do
not replace TV checks for remote navigation, rendering, hardware decoding or HDMI
output. Test code and repository documentation are not packaged into the app.

## Maintainer documentation

- [Device performance and measurement](device-performance.md)
- [Phone setup security and device checks](phone-setup-security.md)
- [Player dependencies, corresponding sources and native verification](player-dependencies.md)
- [Device benchmarking and startup profiles](performance/README.md)
- [MPEG-H decoder sources and rebuilding](app/native/mpegh/README.md)
- [Native FFmpeg filter rebuilding](app/native/ffmpeg-filters/README.md)

## Legal and attribution

Ember's project code is licensed under the [GNU GPL v3](LICENSE). Third-party
components and assets retain their applicable licenses and terms.

Copyright, artwork credits and third-party license texts are available in the
app under **Settings → Legal** and in [the bundled legal notices](app/src/main/assets/licenses/).
Native components have their own licenses and corresponding-source requirements;
see the player and native build documentation before redistributing binaries.
Ember is not affiliated with the services or device manufacturers it integrates.
