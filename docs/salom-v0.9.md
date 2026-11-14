# Salom 0.9.0 — Google sign-in

Native Android Google sign-in replaces SMS, email codes and custom passwords. A single button uses Android Credential Manager, then Firebase Authentication. No Gmail API, SMTP connection or Google Desktop OAuth client is required.

## Configuration

1. Firebase `strike-impact` → Authentication → Sign-in method → Google → Enable; select the support email and save.
2. Project settings → General → Android `com.mukimov.salom`; retain the registered signing SHA-1/SHA-256 and download the updated `google-services.json`.
3. Keep only the Salom Android client in `android/app/google-services.json`. Copy its OAuth `client_type: 3` public client ID into `android/app/src/main/res/values/google_auth.xml` (`salom_google_web_client_id`). This is the web/server client ID, not the Android OAuth client ID.
4. The server uses its existing `/etc/salom-api/firebase-admin.json` to select the expected Firebase project. That private service-account key is never included in Git or APKs.

Only identity scopes are used. The application does not request Gmail access.

## Ownership and migration

The API verifies the signed Firebase ID token's audience, expiration, Google provider, verified email, UID and recent authentication time. It then issues a random 90-day Salom session whose SHA-256 hash is stored server-side. Password/SMS/email-code endpoints are absent; `/auth/config` disables the old providers for older clients.

First-time users choose a unique, case-insensitive username after Google verification. Returning Google users recover the same profile ID and server conversation history. A valid old installation can connect Google from Profile; this preserves the existing username and profile ID and rotates the installation key. Repeating the operation after a lost response recovers the same Google-owned profile. A different Google UID cannot take over the profile or merge with it solely by presenting the same email.

Existing database columns are retained rather than dropping account data. Private email/UID never appear in public profile/search responses. Saved Messages are scoped locally by profile ID. Google recovery restores server conversations; device-only Saved Messages are not synchronized to a new phone.

## Validation

- Isolated backend tests cover Google proof validation, rejected providers/unverified email/stale authentication, first registration, username collisions, legacy binding and retained history, identity collisions, logout and expired sessions. Firebase verification is mocked only in isolated tests.
- Existing push tests cover delivery, retries, invalid tokens and ownership checks.
- HTTPS integration checks use temporary server-owned QA accounts and remove them afterward: Google-only routes, invalid proof rejection, session messaging, privacy, logout, global search and cross-account access protection.
- Android release unit tests and R8 build are run before publication. Final Google account selection/consent requires the user on the device; a successful mocked test is not a real Google sign-in.

Text messages still use HTTPS and server storage; end-to-end encryption and calls are not implemented. Credential Manager requires functioning Google Play services and a Google account on the device.

## Release verification (2026-11-15)

- 20 backend unit tests (15 identity/session and 5 push) passed; HTTPS session, messaging, search and access-control checks passed with temporary fixtures removed.
- 10 Android release unit tests passed; R8 release build completed in 6m 37s. An old Gradle process holding a previous dex file was identified and stopped; this build used a separate output directory.
- Signed APK installed over the existing Itel C671L application and launched successfully; existing conversations remained visible. Google linking screen and its button were inspected on the phone. Google provider enabled and Android signing SHA-1 matching the downloaded OAuth config were verified.
- VersionCode 11; APK 2,849,424 bytes; SHA-256 `d1f39c1967eb9a00386304703a26954a88c72f957409e75fa13e3bba73608745`.
- Published immutable APK: https://mukimov.site/salom/salom-0.9.0.apk. Metadata and the downloaded public APK agree on size and SHA-256.
- User Google account selection/consent is the remaining end-to-end check at the time of this entry.
