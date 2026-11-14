# Salom 0.10.0 — compact chat and attachments

The chat now has a compact header with an accessible vertical three-dot menu, a smaller message field, paperclip and send button. The emoji panel is removed. The paperclip offers Android's photo/video picker or document picker; selecting one attachment does not grant access to the full gallery.

Photos display a sampled inline preview and open on tap. Video and file messages show their filename/size and open through an installed Android viewer. The app provides a private FileProvider URI with temporary read access, rather than exposing a raw filesystem path. Existing text messages and Google sessions are preserved.

## Storage and transfer

- Authenticated streaming binary uploads: 25 MiB per file and 200 MiB per sender account, with quota reserved transactionally before writing. Public API Nginx body limit is raised to 25 MiB.
- The backend checks attachment owner, intended recipient and readiness when committing a message. Retrying a sent message with the same client ID returns the same message; other participants cannot reuse an uploaded attachment.
- Download authorization checks conversation participation and the viewer's clear-history cutoff. Unsaved uploads are readable only by their uploader; expired unattached uploads are removed on subsequent uploads. Server data remains private in `/var/lib/salom-api/attachments` and is outside the website download directory.
- Android copies the selected file with a bounded buffer, caps the remote media cache at about 100 MiB per account, and samples image decoding to at most about 1000 pixels per dimension. Saved Messages retain their attachments in private app files; they are local to that phone and account.
- Files use HTTPS and server storage; end-to-end encryption is not implemented. Videos require an Android app able to play their format. Incoming media requires Salom 0.10.0 on the receiving phone.

## Clear dialog

The menu asks for confirmation and explicitly says that clearing affects the current user. Server conversations use a per-user/per-peer message cutoff, so cleared messages do not return on refresh, restart or another device. The other participant keeps their history. Future messages appear normally. A pending push for an already-cleared message is skipped.

Saved Messages use local removal: all notes and attachment copies for that Saved Messages history are removed from this phone. Device QA must use the temporary media peer, never clear a real friend's chat or the user's existing notes.

## Checks

Backend unit coverage includes private upload/download, idempotence, recipient isolation, size/quota limits, filename sanitization, abandoned uploads and per-user persistent clearing. Public HTTPS smoke checks round-trip a PNG and a large file, verify bytes, block a third account, exercise clearing and remove all synthetic records and files. Existing auth, push, messaging and directory checks are also run.

`backend/ui_media_probe.py` supplies a temporary peer for device checks with a generated QA photo, a plain text file and a public W3C Sintel trailer sample. It verifies an Android-originated attachment and caption, checks clear persistence and removes its files/profile/messages. Test media is not bundled into the APK.

## Release verification — 2026-11-15

- 29 isolated backend tests and 10 Android release unit tests passed. Public HTTPS checks passed for a PNG, a file over the old proxy limit, exact byte integrity, outsider isolation, persistent clearing and future messages. Existing auth, directory, messaging and push checks passed.
- Signed R8 release APK 0.10.0 / versionCode 12 installed over the existing app on Itel C671L. Final build completed in 5m 55s. No recent Salom crash appeared in the crash buffer.
- On-device checks: inline photo preview; MP4 opened and played in the stock viewer; native Android Photo Picker opened; document picker selected only a generated QA image; the image plus caption sent from Android was downloaded and byte-verified by the temporary recipient.
- The real device test caught an HTTP method problem in the client clear operation. It was fixed before publication; clearing then removed the QA history, and it stayed empty after a process restart and reopening the same peer. The temporary recipient still retained its copy.
- Input minimum visual height reduced to 36 dp, header to 40 dp; attachment controls retain accessible touch semantics. Final controls and keyboard focus were checked on the phone. Existing Saved Messages were not cleared.
- QA profile, its messages, server attachment files, staged phone sample and phone QA screenshots were removed. The phone was returned to Saved Messages.
- Published APK: 2,891,748 bytes, SHA-256 `4043f339e76617bc2340ca12add02bbd45cf4dc7f2399d5a9bcbde89d882858a`. Public APK bytes and version metadata were independently checked after publication.
