# Salom 0.11.0 — chat and voice messages

The native chat uses a unified input capsule, attachment menu and an animated microphone/send action. An empty composer records voice; text or a selected attachment switches the action to Send. Message bubbles use softer yellow/slate colors, short-message timestamps sit on the same line when there is space, consecutive messages have compact spacing, and short emoji-only messages display without a solid bubble. The wallpaper is a small cached vector pattern, with no downloaded wallpaper assets.

## Voice recording and playback

- Tap the microphone and grant Android's microphone permission when requested. Recording is explicit and shows a timer, live amplitude bars, Cancel and Stop. Limit: 1 second to 5 minutes.
- Stop produces a draft with playback before sending. The user can listen, add a caption, remove the draft or send it. Nothing is uploaded during recording; upload starts only on Send.
- Native MediaRecorder writes mono AAC in an MPEG-4/M4A container at 44.1 kHz / 96 kbps. The microphone is released on stop, cancel, navigation, disposal and leaving the visible chat; there is no background microphone service. Failed/too-short recordings are removed.
- Voice metadata includes duration and up to 32 bounded amplitude samples derived from the actual recording, rather than a fabricated waveform. The server validates kind, duration and waveform bounds and uses the existing private attachment access checks and quotas.
- An inline MediaPlayer supports play/pause, seeking and elapsed/total duration for voice and other audio attachments. One player per chat prevents overlapping clips. It requests audio focus and releases player/focus on leaving the chat; decoder failures produce a retry message instead of crashing the UI.
- The chat list and incoming push describe an uncaptained voice as «Голосовое сообщение». The previously supplied `ding.mp3` notification sound remains in use.

Saved Messages and their voice attachments remain local to the current account/device, as before. Server conversations/voice clips use HTTPS and existing server storage; end-to-end encryption is not implemented. Older clients should update to 0.11.0 to display the integrated voice player and waveform.

## Checks and manual testing

Backend tests cover voice metadata round trips, invalid duration/waveform/MIME, participant isolation, existing-file compatibility and voice notifications. HTTPS tests use explicitly synthetic transport bytes, verify exact bytes/metadata/access and remove their temporary data. They do not test recording quality or decode an actual microphone clip.

Android unit checks cover waveform normalization/silence/length bounds and emoji presentation, alongside existing username/search/update checks. Release compilation and signing checks are required before publication.

The user explicitly has no connected device for this release and will send the APK to a friend. Real microphone capture, audio quality, playback/seek and visual layout on a physical phone remain manual checks. Do not report those checks as completed.

Friend checklist: install 0.11.0 on both phones; verify the empty composer shows a microphone and typing smoothly switches to Send; grant microphone permission, record 3–5 seconds, Stop, listen, Send; listen and seek on the recipient phone; verify Cancel and backgrounding stop recording; check ordinary text/photo/file messages and the clear-dialog menu still work.

## Release verification — 2026-11-15

- 33 isolated backend tests and 13 Android release unit tests passed. Public HTTPS checks passed for existing photo/files, voice metadata and exact transport bytes, participant access, voice chat preview and per-user clearing. All QA data/files were removed.
- Final R8 release build completed in 7m 47s. Signed APK microphone permission and retained original ding.mp3 bytes were checked. Android signer certificate matches the existing Google OAuth app registration.
- Published version 0.11.0 / code 14: 2996349 bytes; SHA-256 e41cde48b38227c469e4a2321d3523ea26b977c8b15516f65141119e0baf5a23. Public APK bytes and metadata match.
- Real microphone recording, playback, seeking, lifecycle behavior and visual rendering on hardware remain unverified for this release: the user has no connected device and explicitly chose to send the APK to a friend for those checks.
