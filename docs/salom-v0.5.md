# Salom 0.5.0

Version code 7. Updates existing installations in place with the same signing certificate.

## Changes

- System bars and display cutouts reserve safe space around every screen. The chat composer moves above the keyboard. Edge-to-edge rendering remains enabled, with explicit Compose window-inset handling.
- Text conversations now use the authenticated HTTPS API. The earlier “sending unavailable” state is removed. Local Saved Messages remain local.
- Sender identity is taken from the installation credential. Only the two conversation participants can fetch their messages. A client UUID prevents duplicate sends after a failed response and retry.
- Inbox previews and history load from the server. Conversation polling runs every 2.5 seconds and inbox polling every 5 seconds, only while the screen is started. Errors preserve the unsent draft.

## Validation — 2026-11-14

- Release build and Android unit tests passed (`assembleRelease testDebugUnitTest`). Release APK signature verified and version 0.5.0 installed over the existing Itel C671L / Android 14 app.
- On the Itel's 720 × 1600 display, the status bar occupies y=0–72. The chat header text starts at y=86. With keyboard visible from y=1010, the input field ends at y=1003; with keyboard hidden, composer controls end at y=1500, above navigation starting at y=1520.
- Android sent `Salom050check` to an explicitly named temporary QA peer. The peer received it through the public API and replied “Салом! Сообщение получено 💛”; the reply appeared in Android. Inbox preview and both messages remained after force-stop/relaunch and reopening the chat.
- Public HTTPS integration checks passed for cross-account delivery, replies, incremental history, idempotent retries, rejection of conflicting retry payloads, authentication and third-account isolation. Fixtures and their messages were removed afterward.
- The friend's Samsung was not connected for direct testing. The system inset fix uses Android's provided bar/cutout/IME values rather than model-specific offsets.

## Scope

Both participants need version 0.5.0 or newer and internet. Messages refresh while the application is open. There are no background push notifications, calls, media sending or read receipts yet. Messages are stored on the server and transported over HTTPS; end-to-end encryption and cross-device account recovery are not implemented. Phone/email profile fields remain unverified and local. Device address-book entries are not automatically treated as verified Salom accounts.
