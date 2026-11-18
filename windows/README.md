# SALOM Desktop client

The WPF client targets Windows x64 with .NET 10. It provides the SALOM dark interface, Google sign-in flow, people search, text chats, local SQLite history, a per-Windows-user DPAPI-protected session token, authenticated WebSocket message/presence/typing events with reconnect and catch-up sync, and bounded file upload/download using the server's private attachment routes.

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

The publish output is a standalone executable, but is not code-signed and there is no setup/uninstaller package yet. Desktop attachments currently send and download files up to 25 MiB; automatic image/video previews, voice messages, Windows notifications, and desktop calls are not implemented. Android-to-Windows calling is not verified. Do not describe the Windows app as feature-complete or release-ready until OAuth, live API/WebSocket, attachment access-control, call, notification, and installer checks are completed.
