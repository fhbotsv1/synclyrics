# SyncLyrics

An Android companion app that detects music playing in other apps and displays synchronized, line-by-line lyrics.

## Current foundation
- Kotlin, Jetpack Compose and Material 3
- Android MediaSession detection through user-enabled Notification Listener access
- Reads current track metadata, playback state, position and duration
- Fetches timestamped lyrics from LRCLIB when available
- Imports local .lrc files as a fallback
- Highlights and scrolls to the current lyric line
- Minimum Android 8.0 (API 26), compile SDK 36, JDK 17
- Application ID: com.littlebird.synclyrics

## Setup
1. Install and open SyncLyrics.
2. Tap Enable notification access and allow SyncLyrics in Android Settings.
3. Start music in another app that exposes an Android media session.
4. Return to SyncLyrics. Track information and matching lyrics should appear.
5. If online synced lyrics are unavailable, use Open LRC to import a timestamped lyrics file.

## Notes
- Android does not provide a universal way for one app to inspect another app's audio. SyncLyrics uses Android's MediaSession APIs; it does not record or analyze audio.
- Detection depends on the music app publishing a media session and the device allowing notification-listener access.
- LRCLIB availability and network access are required for automatic online lyric lookup.
- Local LRC files are supported for songs without an online match.
