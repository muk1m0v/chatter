# SALOM Desktop client

The WPF client targets Windows x64 with .NET 10. It provides the SALOM dark interface, Google sign-in flow, people search, text chats, local SQLite history, a per-Windows-user DPAPI-protected session token, an account-wide authenticated WebSocket subscription with reconnect and catch-up sync, bounded file upload/download using the server's private attachment routes, and Windows tray-balloon notifications when the window is in the background. One-to-one voice/video calls use WebRTC in WebView2 with authenticated SALOM call signaling shared with Android.

## Configuration

Set these values in the Windows user's environment before starting the client:

- `SALOM_API_BASE_URL` — HTTPS base ending in `/salom/api/`; defaults to `https://mukimov.site/salom/api/`.
- `SALOM_GOOGLE_CLIENT_ID` — Google OAuth desktop client ID.
- `SALOM_GOOGLE_CLIENT_SECRET` — OAuth desktop client secret used only for the authorization-code exchange. Desktop OAuth clients are public clients; this value is not a server secret and must not be treated as one.
- `SALOM_FIREBASE_WEB_API_KEY` — Firebase Identity Toolkit API key for the same Firebase project.

The desktop OAuth client must belong to the Firebase project and Google provider configuration used by SALOM. The Android `google-services.json` does not contain the desktop OAuth client settings. A real account sign-in still needs owner-side Google/Firebase setup and live-backend verification. The desktop WebSocket path is derived from `SALOM_API_BASE_URL` and expects the reverse proxy to expose the `/ws` endpoint under that base.

## Build

```powershell
dotnet restore windows/Salom.Desktop/Salom.Desktop.csproj
dotnet publish windows/Salom.Desktop/Salom.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

The installer build script is `windows/build-installer.ps1`. It creates an unsigned self-contained per-user setup package that installs into LocalAppData and adds Start menu and desktop shortcuts. Desktop attachments currently send and download files up to 25 MiB; inline previews and Windows voice-message recording are not implemented. The app requires the Microsoft Edge WebView2 Runtime for calls. Android-to-Windows calls, OAuth, live API/WebSocket, attachment access-control, and notification behavior still require owner testing. Do not describe the Windows client as release-ready until those checks are completed.

See `docs/0.15.1-allowcookie-test.md` for the owner/friend test checklist and current package notes.
