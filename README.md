# Салом / Salom

Native Android messenger prototype with a saturated yellow accent. The repository remains named `chatter`.

## First test build — 0.1.0-test

The `android` project contains the Salom app (`com.mukimov.salom`): phone/email entry with format validation, demo chat list and search, contacts, local message composition and persistence, profile editing, and a saved dark theme preference. Messages and profile data stay on the device. This is a local interface prototype: there is no SMS/email verification, backend authentication, remote message delivery, calls, or push notifications yet. The launcher icon is a native vector resource.

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
