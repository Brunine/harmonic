# Changelog

Every release bumps both numbers in `app/build.gradle.kts`:

- `versionName`: what people see (`MAJOR.MINOR.PATCH`)
  - **PATCH** (1.1.**1**): bug fixes only
  - **MINOR** (1.**2**.0): new features
  - **MAJOR** (**2**.0.0): big changes, or changes that break existing data or settings
- `versionCode`: whole number, **+1 on every release**. Android refuses to install an update
  whose `versionCode` is not higher than the installed one.

| Version | versionCode | Date | Status |
|---|---|---|---|
| 1.1.0 | 2 | 2026-10-20 | In testing |
| 1.0.0 | 1 | 2026-10-19 | Released |

## [1.1.0] - 2026-10-20

### Added
- **Colour themes** (Settings → Appearance): light, dark or system mode; seven preset palettes;
  wallpaper colours (Android 12+); colours from the current song's cover; pure black for
  OLED screens.
- **More music sources** in Search: SoundCloud, Deezer and Apple Music, each result labelled
  with its source.
  - SoundCloud streams directly (no downloads; Go+ tracks don't play).
  - Deezer and Apple Music songs play through the matching YouTube Music track; matches are
    remembered, so downloads and offline playback work.
- Import playlists and albums from SoundCloud, Deezer and Apple Music links, and open those
  links when shared to the app.
- **Jam** tab: friends nearby share one queue over Nearby Connections (Bluetooth, without
  disturbing wireless Android Auto). Guests add, reorder and remove songs; the host can let
  guests control playback; optional "Play on every phone" mode.

### Changed
- Database version 1 → 2 (adds song matches). Existing playlists, likes and downloads are kept.

### Known limitations
- Spotify not supported (its API now needs your own developer app and Premium).
- "Play on every phone" sync is approximate (a few hundred milliseconds).

## [1.0.0] - 2026-10-19

### Added
- Audio-only playback of YouTube and YouTube Music, with the thumbnail as album art.
- Background and lock-screen playback (Media3 MediaLibraryService, foreground service,
  notification, Bluetooth/headset controls, resume last queue).
- Android Auto: browse Recent, Playlists, Downloads and Liked; search, including voice.
- Offline downloads; playlists mixing online and downloaded songs; playlist import from
  YouTube Music links.
- No ads; SponsorBlock skips non-music parts of music videos.
- Separate audio quality for Wi-Fi, mobile data and downloads; streaming cache; data-saver
  artwork; audio offload; burst buffering for lower battery use.
- Optional Google sign-in, used only for age-restricted tracks.
- Share YouTube links to the app to play them.