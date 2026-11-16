# Release signing for SALOM Android

Use the existing SALOM upload key. Do not create a replacement key for an update build: Android will reject an APK signed with a different certificate as an in-place upgrade.

## Supply the existing key to Gradle

Keep the keystore outside the Git worktree. Provide these values to the Gradle process through environment variables or untracked local Gradle properties:

- `SALOM_RELEASE_STORE_FILE`: absolute keystore path
- `SALOM_RELEASE_STORE_PASSWORD`: keystore password
- `SALOM_RELEASE_KEY_ALIAS`: existing upload-key alias
- `SALOM_RELEASE_KEY_PASSWORD`: key password

Do not commit the keystore, passwords, or a properties file containing them. Do not paste the passwords into chat. The Gradle release task refuses to build when the file or any value is missing.

## Check before building

1. Confirm the key certificate matches the currently distributed SALOM APK certificate.
2. Build `:app:assembleRelease` only after the match is confirmed.
3. Verify the APK package name, version `0.15.0`, version code `19`, and signing certificate before calculating its SHA-256.
4. Keep the APK unpublished until the separately approved release step.
