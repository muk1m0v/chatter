# Salom directory and messaging API

Purpose: username lookup and authenticated text conversations across Android installations. This service is independent of the APK page and existing applications on the VPS.

| Endpoint (prefix `/salom/api`) | Authentication | Behavior |
| --- | --- | --- |
| GET `/health` | None | Database readiness |
| PUT `/profile` | Bearer installation key | Idempotent enrollment or own profile update; 409 for occupied username, 403 for another owner's ID |
| GET `/search?q=prefix` | Registered Bearer installation key | Up to 20 active public profiles, case-insensitive username prefix; optional @ |
| POST `/messages` | Registered Bearer installation key | Send to an active recipient; client UUID makes retries idempotent; 4,000 character limit |
| GET `/messages?peer_id=UUID&after=0` | Registered Bearer installation key | Only the caller's conversation with this peer; batches of 100, ascending server IDs |
| GET `/conversations` | Registered Bearer installation key | Up to 100 latest conversations belonging to the caller |
| PUT `/push-token` | Registered Bearer installation key | Bind or disable the caller's FCM token; one installation per current account |
| GET `/profiles/{id}` | Registered Bearer installation key | Resolve an active public profile for a notification's chat link |

Public response fields: id, name, username, is_active. No phone, email, token or key hash is returned. The server never receives the address book.

Messages are stored in SQLite and transferred over HTTPS; they are not end-to-end encrypted. Sender identity comes from the installation key, never a request-supplied sender ID. Android polls history only while its screen is started. Version 0.6 adds FCM notifications in the background, independent of polling.

Server FCM credentials live at `/etc/salom-api/firebase-admin.json`, root-owned and group-readable only by `salom-api` (0640 inside a 0750 directory). They never enter the APK or Git. Android's `google-services.json` is client configuration, not a server private key. Install the pinned Python dependencies before deploying; `push.py` is deployed alongside `app.py`.

Each new message inserts an outbox row in the same SQLite transaction. The single service worker sends HTTP v1 notification+data payloads, using a one-day TTL, high priority, private lockscreen visibility and a stable per-message notification tag. Network failures retry with backoff; unregistered tokens are removed. Delivery is at least once; a stable tag replaces a repeated notification. Google acceptance is not proof of device display or reading. Existing message POST retries do not enqueue another outbox row.

`test_push.py` uses an isolated temporary SQLite database and fake HTTP responses to check queue idempotency, payload routing, network retry, token removal, disable and binding isolation. `ui_push_probe.py` creates a controlled sender for physical Android tests, then removes its profile, messages and outbox rows with `cleanup`. Force-stopping an app from Android settings prevents FCM delivery until the user opens it again; ordinary backgrounding is different.

`qa_messaging_live.py`, `qa_auth_live.py`, and `qa_media_live.py` use public HTTPS and create/delete synthetic production-side QA fixtures. Run them only as deliberate live QA tasks; they are intentionally named `qa_*` so `unittest discover -p 'test_*.py'` cannot execute them. `ui_messaging_probe.py create @username` creates a temporary controlled peer that replies only to the exact QA text `Salom050check`. Run its `cleanup` command after Android testing to remove that peer and cascade-delete its messages. No real user's conversation is used by these tests.

On the authorized VPS, create `/opt/salom-api/.venv` with Python 3.14 and install `requirements.lock`. Upload `app.py`, `requirements.txt`, `salom-api.service`, and `deploy.py` to `/tmp/salom-api-deploy`, then run its `deploy.py` as root. The script preserves existing Nginx routes and backups existing configuration/database. The service itself runs as `salom-api` with a private state directory and loopback-only Uvicorn.

`smoke_test.py` checks two independent identities over public HTTPS and removes only its own generated fixtures. `ui_probe.py create @username` checks a real target from an independent temporary account and publishes a clearly named QA fixture for Android UI testing; run `ui_probe.py cleanup` afterward. Never leave QA fixtures in the public directory.

`requirements.lock` records the installed versions. Runtime SQLite data and installation credentials are not committed to Git. Account recovery and verified phone identity are future work; update Android in place to preserve its installation credential.

`backup.py` creates a consistent root-private SQLite snapshot in `/var/backups/salom-directory` without interrupting the running API. It is a manual backup helper; no recurring off-server backup is configured by this change.
