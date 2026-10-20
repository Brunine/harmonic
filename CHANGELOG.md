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
| 1.5.0 | 6 | 2026-10-20 | In testing |
| 1.4.0 | 5 | 2026-10-20 | Released |
| 1.3.0 | 4 | 2026-10-20 | Released |
| 1.2.0 | 3 | 2026-10-20 | Released |
| 1.1.0 | 2 | 2026-10-20 | Released |
| 1.0.0 | 1 | 2026-10-19 | Released |

## [1.5.0] - 2026-10-20

### Added
- **Like and shuffle buttons in the car**: Android Auto's player screen gets a heart (like or
  unlike the playing song) and a shuffle button. They also appear in the media notification and
  on other controllers (lock screen, Wear OS).
- **Quick picks in Android Auto**: a new first tab with recommendations based on the last song
  you played, the same list as the Home screen. The last list is kept on the phone, so the tab
  opens instantly, even without signal, and refreshes in the background (on Wi-Fi, or on mobile
  data only when there is nothing to show yet).
- **Covers in the car**: Quick picks and Playlists are shown as a grid of covers, and every
  playlist shows its first song's cover.
- **Download liked songs** (Settings → Data & battery, off by default): while on Wi-Fi, every
  song you like is downloaded by itself, so your likes always play offline. Songs liked on mobile
  data follow the next time you're on Wi-Fi. Unliking never deletes a download.
- **Home screen widget**: cover, song, artist and previous / play-pause / next, in the synthwave
  colours. Tap the cover or title to open the app. Long-press the home screen → Widgets →
  Harmonic to add it.

### Changed
- Android Auto tabs are now Quick picks, Recent, Playlists and Downloads. "Liked songs" moved to
  the first tile inside Playlists (Android Auto shows at most four tabs).

### Notes
- No database changes: updating from 1.4.0 keeps everything.

## [1.4.0] - 2026-10-20

### Added
- **Lyrics**: new lyrics button on the player. Synced lyrics scroll with the song and highlight
  the current line; tap a line to jump there. Plain lyrics when no synced version exists. Lyrics
  come from LRCLIB (free, no account), are only fetched when you open them, and are kept on the
  phone afterwards.
- **Artist pages**: header picture, top songs, albums, singles, videos, similar artists and the
  artist's bio, straight from YouTube Music. **Shuffle** plays the artist's songs. Open one by
  tapping the artist name on the player, from "Go to artist" in any song's menu, from the new
  **Artists** search filter (YouTube tab), or from a shared YouTube Music artist link.
- **Album pages**: YouTube Music albums now open as proper albums with track numbers, release
  year, song count and length, and a link to the artist. Play, shuffle, save to library and
  download all from the same screen. Shared album links open here too.
- **Volume normalization** (Settings → Playback, on by default): songs mastered much louder than
  others are turned down to YouTube's reference level, using the loudness value YouTube already
  sends with each song (no extra data). Quiet songs are left as they are.

### Changed
- Navigation prompts, the sleep timer fade-out and normalization now share the volume control
  without overriding each other.

### Notes
- Database version 3 (adds the loudness column). Updating from 1.3.0 keeps everything.
- Normalization starts working for each song the next time it is streamed or downloaded;
  songs already fully cached or downloaded before 1.4.0 play at their usual volume.

## [1.3.0] - 2026-10-20

### Added
- **Português (Brasil)**: the whole app is translated. It follows the phone's language; on
  Android 13+ you can also pick the app's language on its own (Settings → App language).
- **Sleep timer**: moon button on the player. 15, 30, 45, 60 or 90 minutes, or "end of this
  song". The music fades out over the last 10 seconds, then pauses. The time left shows next to
  the button.
- **Search history**: your last 20 searches show when the search box is empty. Tap to search
  again, remove one, or clear them all.
- **Drag to reorder**: hold the ≡ handle to move songs in a playlist or in the queue.
- **Backup & restore** (Settings → Backup): saves playlists, likes, play history, remembered
  Deezer/Apple Music matches, search history and settings to a `.json` file you choose (Drive,
  Downloads…). Restoring merges it into the library: playlists with the same name are replaced,
  a song liked on either phone stays liked and the higher play count is kept. Downloaded audio files are not in the backup.

### Changed
- All on-screen text, notifications and error messages now come from translatable resources.

### Notes
- No database changes: updating from 1.2.0 keeps everything.

## [1.2.0] - 2026-10-20

### Added
- **Synthwave design**: Harmonic's own look. Neon pink and cyan on deep purple, a wide
  retro-futuristic font (Orbitron) for titles, a sunset-and-grid backdrop on the player, glowing
  cover art, neon seek bar and play button, restyled mini player, navigation bar and launcher icon.
  It's the new default palette; the other palettes and the light theme are still in Settings.
- **Where each song comes from**: coloured source labels on every song (YouTube, SoundCloud,
  Deezer, Apple), an **All sources** search tab that searches every service at once, "Playing from
  …" on the player ("via YouTube Music" for Deezer and Apple Music), and the source in Android
  Auto and notification subtitles.
- **When something else needs the sound** (Settings): choose Pause, Lower volume or Keep playing
  separately for calls, recording (voice messages, videos), other apps' short sounds, navigation
  and Assistant prompts, and another music app (Pause or Keep). Plus "Resume afterwards".
- **Crash reports**: crashes are saved on the phone; Settings → Crash reports → Share.

### Changed
- Audio focus is now handled by the app itself instead of the player library, to follow the
  settings above.
- Default theme is dark with the Synthwave palette (only for people who never changed it).

### Known limitations
- A music visualizer can't be shown in Android Auto: Android Auto doesn't let music apps draw on
  the car screen.
- On Android 12 and newer, when another music app starts, Android fades Harmonic out even with
  "Keep playing".

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
