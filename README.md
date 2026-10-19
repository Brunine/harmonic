# Harmonic

An audio-only YouTube / YouTube Music player for Android, built for low data and battery use.
Inspired by InnerTune, OuterTune and YMusic.

- **Background playback**: `MediaLibraryService` + ExoPlayer (Media3). Keeps playing in other apps
  and with the screen locked; lock-screen, notification, Bluetooth and Wear OS controls.
- **Audio only**: only the audio stream is ever requested. No video decoder is created; the video
  thumbnail is center-cropped into a square album cover.
- **Android Auto**: browse Recent, Playlists, Downloads and Liked; search, including voice
  ("Hey Google, play … on Harmonic").
- **Downloads**: offline copies of any song or video (audio). Downloaded songs play offline from
  every screen, playlist and Android Auto.
- **Playlists**: mix online and downloaded songs freely. Import any YouTube / YouTube Music
  playlist or album by link, or save one from search.
- **No ads**: streams are fetched directly, so ads are never requested. Optional SponsorBlock
  skips non-music intros/outros and sponsor segments inside music videos.
- **Optional Google sign-in**: only used to play age-restricted tracks.
- **Share to app**: share a YouTube / YouTube Music link to Harmonic to play it or open the playlist.

## Building the APK

### Option A: GitHub Actions (no local setup)

1. Push this folder to a GitHub repository.
2. The **Build APK** workflow runs on every push (or run it manually from the *Actions* tab).
3. Download `harmonic-release-apk` from the finished run, unzip, and install the APK.

Without signing secrets the APK is signed with a throwaway debug key that changes every run,
so updates would need an uninstall first. To get installable updates, create a key once:

```bash
keytool -genkeypair -v -keystore release.jks -alias harmonic -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks   # copy the output
```

Then add these repository secrets (*Settings → Secrets and variables → Actions*):
`KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`harmonic`), `SIGNING_KEY_PASSWORD`.

### Option B: Android Studio / command line

Requires JDK 17 and the Android SDK (Android Studio installs both).

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

For a stable signing key locally, create `keystore.properties` in the project root
(it is git-ignored):

```properties
storeFile=release.jks
storePassword=...
keyAlias=harmonic
keyPassword=...
```

`./gradlew assembleDebug` builds a debug variant that installs alongside the release one.

## Android Auto

Android Auto hides media apps that were not installed from the Play Store. On the phone:
open Android Auto settings → tap **Version** about 10 times to enable developer mode →
⋮ menu → **Developer settings** → enable **Unknown sources**. Harmonic then appears in the car's
app launcher.

## How data and battery use are kept low

| Area | What the app does |
|---|---|
| Stream format | Audio-only adaptive streams. Separate quality for Wi-Fi, mobile data and downloads. Default on mobile is Low (~50 kbps Opus, about 22 MB/hour). Opus is preferred at equal bitrate. |
| Repeat listens | A streaming cache (512 MB by default, LRU) means replays cost no data. Cached or downloaded songs never trigger a stream lookup at all. |
| Buffering | Buffers in bursts (fill to 2 min, refill below 30 s) so the radio can sleep between bursts. Playback starts after 1 s of audio. |
| Decoding | Audio-only renderer; audio offload lets the DSP decode with the CPU asleep (toggle in settings if a device glitches). |
| Wake-ups | No position polling in the background. SponsorBlock skips are scheduled player messages, not timers. The UI polls position only while visible. |
| Artwork | Smallest sufficient thumbnail size; ~226 px covers on mobile data. Images are cached on disk. |
| Recommendations | Home recommendations load automatically on Wi-Fi only; on mobile data they load on tap. |
| Skips | The next track's stream URL is resolved ahead of time (metadata only) for instant skips. |
| Downloads | Optional Wi-Fi-only downloads (default on). |

## Architecture

```
ui/                Compose screens, PlayerConnection (MediaController wrapper)
playback/
  PlaybackService  MediaLibraryService: ExoPlayer, notification, Android Auto tree, radio,
                   SponsorBlock, queue persistence and resume
  StreamResolver   video id → audio URL (quality by network, URL cache, signed-in fallback)
  ArtworkProvider  square, cached artwork over content:// for notification and Android Auto
download/          Media3 DownloadManager + DownloadService into a dedicated cache
data/
  remote/          NewPipeExtractor (search, streams, playlists), request shaping for
                   googlevideo.com, signed-in player for age-restricted tracks, SponsorBlock
  db/              Room: songs, playlists, liked/history, cached audio formats
  prefs/           DataStore settings
```

Read path for audio: **download cache → streaming cache → resolve URL → network**. Everything is
keyed by video id, so a song downloaded once plays offline wherever it appears.

## Maintenance notes

- **YouTube changes break extraction regularly.** The fix is almost always bumping
  `newpipeExtractor` in `gradle/libs.versions.toml` to the latest
  [NewPipeExtractor release](https://github.com/TeamNewPipe/NewPipeExtractor/releases).
- **Age-restricted playback is best-effort.** The anonymous clients used by NewPipeExtractor
  cannot use a login, so signed-in playback calls YouTube's player endpoint directly
  (`AuthenticatedPlayer.kt`). If it stops working, update the client versions listed there.
  Cookies are stored in app-private storage and only sent to youtube.com for those tracks.
- This is an unofficial client and is not affiliated with YouTube or Google. Using it may be
  against YouTube's Terms of Service; it is intended for personal use.
