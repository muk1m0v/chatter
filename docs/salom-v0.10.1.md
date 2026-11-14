# Salom 0.10.1 — incoming notification sound

The user-supplied `ding.mp3` is bundled byte-for-byte as `android/app/src/main/res/raw/salom_ding.mp3`. Source SHA-256: `35b9fe02caff64849844b8075176a96cc6fde13a7ef4eac0a1bce14ff81d0374`.

Foreground Firebase messages use NotificationCompat with the bundled audio URI. The Application entry point creates the channel even when only the background Firebase service starts. Background FCM notifications specify raw sound `salom_ding` and channel `salom_messages_ding_v1`; the manifest fallback uses the same channel. Android 8+ uses a new channel because Android does not allow an application to change an existing channel's sound. Resource references retain the sound in the optimized APK, and its URI uses a resource name rather than a version-dependent numeric ID.

Migration retains the old channel's importance, silence, vibration and, on Android 11+, explicitly selected custom sound; app-wide notification permissions, silent mode and Do Not Disturb remain under the device user's control. Android 7 uses the explicit NotificationCompat sound. Older APKs without the raw resource/channel continue using Firebase's system fallback until upgraded.

Validation includes FCM payload delivery/retry/access tests, release build/unit checks, comparison of MP3 bytes inside the APK, signature verification and a controlled incoming push on the connected Itel. Temporary QA messages and the QA sender are removed afterward. Device hearing confirmation is recorded separately from the configured channel and OS playback evidence.

## Release verification — 2026-11-15

- 6 FCM delivery/retry/ownership tests and 10 Android release unit tests passed. Final R8 build completed in 5m 51s.
- The final APK contains exactly one MP3, 88,023 bytes, whose SHA-256 matches the user's original file. The raw resource symbol `salom_ding` is retained after resource optimization, even though its ZIP filename is shortened.
- VersionCode 13 installed over the existing app on Itel C671L. Android reports channel `salom_messages_ding_v1`, importance 4 and sound `android.resource://com.mukimov.salom/raw/salom_ding`.
- The phone initially used VIBRATE mode. The user enabled normal sound; the test then sent one controlled incoming FCM notification while Salom was in the background and the screen was off. The user explicitly confirmed that the exact supplied sound played.
- QA sender, its message and push job were removed afterward. App relaunched successfully; no recent Salom crash was found. No global volume/ringer setting was changed by automation.
- Published APK: 2,979,936 bytes; SHA-256 `aa904f7f55ea15da10a6217ae9e0fc2181e56c41c2e999f82d8dd7a88c917f24`. Public download bytes match the version metadata.
