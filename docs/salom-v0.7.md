# Salom 0.7.0 — in-app updates

Version code 9, Android 7.0+. Settings contains an update card and Chats links to it when an update is available.

## Trustworthy version status

The application checks `https://mukimov.site/salom/version.json` at startup and when Settings is opened. Successful foreground checks are throttled to 15 minutes outside Settings; the manual button forces a fresh request. Responses bypass HTTP caches and are limited to 32 KiB.

Only a successful validated response with an equal version code and version name displays the green check and “У вас последняя версия”, together with the check time. Network/metadata errors show a retry state. A lower server version code shows a neutral local-ahead state. An incompatible newer Android requirement is shown separately. No saved metadata is treated as a fresh server check after restarting the process.

## Download and installation

- Download happens only after a button press. Progress, pause and retry are available. A retained partial file can be resumed with an HTTP byte range; a 200 response restarts it cleanly.
- Only HTTPS URLs on `mukimov.site`, with the exact Salom versioned/latest APK path and no credentials, query, fragment or alternate port, are accepted. Metadata sizes and checksums are validated.
- Downloads remain in the application's private `files/updates` directory. Before installation the APK's size, SHA-256, package name, version code/name and signing certificates are checked against the metadata and installed app.
- FileProvider grants the Android installer temporary read access to this directory only. Android's installation-source permission and standard confirmation remain part of the flow. Returning after allowing this source continues installation. Cancelling installation retains the verified file for another attempt.
- The release publisher uses immutable versioned APK URLs and atomically publishes version metadata after Nginx has loaded the APK route. Published bytes cannot be silently replaced under an existing version filename.

## Scope

Version 0.6 and earlier do not contain this updater. Users install 0.7 once from the download page; subsequent updates can use Settings. The update retains the application ID, signing certificate and existing data. Notifications introduced in 0.6 remain enabled. No APK is downloaded automatically on startup.

## Validation — 2026-11-14

- `assembleRelease testDebugUnitTest` passed. All nine tests passed: four existing search tests and five update-policy tests covering equal/conflicting build labels, local-ahead state, newer/incompatible releases, untrusted URLs, malformed checksums and oversized downloads.
- Release APK signature was verified with the Android build tools. The manifest contains the expected package, version 0.7.0 and version code 9.
- Published version metadata references the immutable 0.7.0 APK URL. The public APK was downloaded and SHA-256 matched the local final APK and metadata: `f7f436e7ae173355818fbf4965ee0eb65d3e834afe638542328ca0522b8641ba` (1,856,702 bytes).
- The public APK route returned HTTP 206 with the exact expected Content-Range and byte count for a resumed request starting at byte 1024. The homepage showed 0.7.0 and the messaging API remained healthy with push configured.
- The Itel was disconnected during this release's work. The Android permission/installer flow and visual badge still require physical-device verification; no successful device installation is claimed here.
