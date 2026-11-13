# Chatter

Private repository for a native Android messenger. Preparation is complete; messenger features and backend have not been implemented yet.

## Verified development baseline

- Kotlin 2.2.21 and Jetpack Compose, Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 21.
- Native APK builds without Android Studio or an emulator.
- Installation, launch, Compose rendering, button interaction, and relaunch verified on an itel C671L running Android 14.
- A repeated offline build, USB installation, and launch completed in 59.3 seconds on the development PC. The unchanged build took 48 seconds; this is not a benchmark for future code changes.

The `diagnostics/android-smoke` app is the verified preparation test, not a messenger implementation.

## Build, install, and open on the connected phone

Run from PowerShell at the repository root:

```powershell
.\tools\build-install.ps1
```

Authorize USB debugging on the phone. With multiple devices, pass `-DeviceSerial`. Use `-Offline` after all dependencies have been downloaded. The script stops if the build, installation, or launch fails. It generates an ignored `local.properties` pointing to the local Android SDK.

Build source and wrapper files are backed up in Git; downloaded libraries, virtual environments, machine paths, caches, logs, APK/AAB artifacts, and local databases are excluded. Built APK files can be distributed separately.

## Server

See [the server readiness report](docs/server-readiness.md). The audit was read-only: no existing service, firewall rule, package, or database was modified.
