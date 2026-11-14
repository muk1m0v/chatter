# Салом / Salom

Native Android messenger prototype with a saturated yellow accent. The repository remains named `chatter`.

## Current version — 0.3.1

The `android` project contains Salom (`com.mukimov.salom`): phone/email profile entry, real device contacts with runtime permission and background loading, private Saved Messages, profile editing, and light/dark themes. Fictional contacts and demo labels were removed in 0.2.0. The default build is an R8-optimized release. See [the 0.2 notes](docs/salom-v0.2.md).

Version 0.2.1 makes the toolbar, search, bottom navigation, chat rows and profile more compact. See [layout notes](docs/salom-v0.2.1.md).

Version 0.3.0 adds a local @username profile/search example, native invitation sharing, and softer chat styling. APK download page is hosted at https://mukimov.site/. See [release notes and scope](docs/salom-v0.3.md).

Version 0.3.1 adds prefix Smart Search across the available profile and address book, explicit active/inactive/unknown contact routing, and bottom-aligned short conversations. See [scope and checks](docs/salom-v0.3.1.md). Account discovery and remote messaging remain unimplemented; no fictional registered contacts are shown.

Data stays on the device. SMS/email verification, backend authentication, remote message delivery, calls, and push notifications have not been implemented. Device contacts are not presented as registered Salom users.

## Verified development baseline

- Kotlin 2.2.21 and Jetpack Compose, Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 21.
- Native APK builds without Android Studio or an emulator.
- Installation, launch, Compose rendering, button interaction, and relaunch verified on an itel C671L running Android 14.
- A repeated offline build, USB installation, and launch completed in 59.3 seconds on the development PC. The unchanged build took 48 seconds; this is not a benchmark for future code changes.

The `diagnostics/android-smoke` app remains the original environment test. The build script now targets `android` by default.

## Build, install, and open on the connected phone

Run from PowerShell at the repository root:

```powershell
.\tools\build-install.ps1
```

Authorize USB debugging on the phone. With multiple devices, pass `-DeviceSerial`. Use `-Offline` after all dependencies have been downloaded. The script stops if the build, installation, or launch fails. It generates an ignored `local.properties` pointing to the local Android SDK.

Build source and wrapper files are backed up in Git; downloaded libraries, virtual environments, machine paths, caches, logs, APK/AAB artifacts, and local databases are excluded. Built APK files can be distributed separately.

## Server

See [the server readiness report](docs/server-readiness.md). The audit was read-only: no existing service, firewall rule, package, or database was modified.
