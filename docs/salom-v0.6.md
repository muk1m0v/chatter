# Salom 0.6.0 — notifications

Version code 8; Android 7.0 / API 24 or newer, required by Firebase Messaging 26.0.2 in BoM 35.2.0. Google services plugin 4.6.0. The APK retains the existing application ID and signing certificate for in-place updates.

## Behavior

- Firebase Cloud Messaging delivers message notifications while the application is in the background. Android 13+ asks for notification permission after sign-in. Settings link to the system's notification controls.
- WorkManager registers the installation's FCM token through the authenticated Salom API, retries with network constraints/backoff, updates after token refresh and disables registration after sign-out or notification denial.
- Notification taps resolve the sender's public profile through the authenticated API and open that conversation, including when the app starts cold.
- The service writes a durable notification job in the message transaction and sends it separately over FCM HTTP v1. Network failures do not lose the message or delay the sender's response. Notification tags and message idempotency prevent separate duplicate notifications on retries.
- Notification text is a short message preview. Lock-screen visibility is private. Firebase receives the notification payload and a device registration token; address-book data, phone and email are not sent to FCM. Analytics is not included.

## Deployment and validation

- Client config and service credential were verified to use the same Firebase project; only the `com.mukimov.salom` client is retained in Android config.
- Service credentials are outside the repository and APK at `/etc/salom-api/firebase-admin.json` on the authorized VPS, restricted to root and the service group.
- OAuth authentication and FCM API access were verified with a validation-only request to a deliberately invalid token; no device delivery was attempted by this check.
- Five isolated SQLite tests passed: outbox idempotency/payload, network retry, unregistered token removal, disabled registration and cross-account token binding rejection.
- Existing public HTTPS messaging integration checks passed: delivery, replies, incremental fetch, inbox, authentication and third-account isolation.
- On Itel C671L / Android 14, notification permission was granted and the FCM token registered on the authenticated backend. With the app backgrounded and the display in Dozing state, a controlled QA message was accepted by Google and appeared in the Android notification drawer. Tapping it opened the matching chat and loaded its message. A second QA message while the app was foregrounded also produced a notification. `am kill` did not reliably terminate this device's process, so process-death delivery was not independently confirmed. No real friend was messaged by these tests.
- `assembleRelease testDebugUnitTest` passed, then a final `assembleRelease` passed after the chat-switch isolation change. The final release signature was verified, version code 8 installed in place, and notification navigation from Saved Messages to the QA sender was checked on that final APK. The APK contains no server credential file.
- The published APK was downloaded again and its SHA-256 matched both the local final APK and public version metadata. API and Nginx remained active. QA profiles/messages/jobs were removed, followed by a consistent SQLite backup. Final APK: 1,839,678 bytes, SHA-256 `8a5480bdcf994dd3112c062c5f9cf490394bf1234e2e6c0f871a890129046218`.

## Limits

Google Play services, network access and notification permission are required. Android force-stop, disabled notification channels and vendor battery restrictions can prevent or delay notifications. FCM acceptance does not mean a person read the message. Calls, media messages, verified phone/email login, account recovery and end-to-end encryption remain outside this release.
