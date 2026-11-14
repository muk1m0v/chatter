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

Public response fields: id, name, username, is_active. No phone, email, token or key hash is returned. The server never receives the address book.

Messages are stored in SQLite and transferred over HTTPS; they are not end-to-end encrypted. Sender identity comes from the installation key, never a request-supplied sender ID. Android polls only while its screen is started; background push notifications are not implemented.

`test_messaging.py` checks delivery, replies, incremental reads, retry deduplication, inbox membership and isolation from a third account through public HTTPS. `ui_messaging_probe.py create @username` creates a temporary controlled peer that replies only to the exact QA text `Salom050check`. Run its `cleanup` command after Android testing to remove that peer and cascade-delete its messages. No real user's conversation is used by these tests.

On the authorized VPS, create `/opt/salom-api/.venv` with Python 3.14 and install `requirements.lock`. Upload `app.py`, `requirements.txt`, `salom-api.service`, and `deploy.py` to `/tmp/salom-api-deploy`, then run its `deploy.py` as root. The script preserves existing Nginx routes and backups existing configuration/database. The service itself runs as `salom-api` with a private state directory and loopback-only Uvicorn.

`smoke_test.py` checks two independent identities over public HTTPS and removes only its own generated fixtures. `ui_probe.py create @username` checks a real target from an independent temporary account and publishes a clearly named QA fixture for Android UI testing; run `ui_probe.py cleanup` afterward. Never leave QA fixtures in the public directory.

`requirements.lock` records the installed versions. Runtime SQLite data and installation credentials are not committed to Git. Account recovery and verified phone identity are future work; update Android in place to preserve its installation credential.

`backup.py` creates a consistent root-private SQLite snapshot in `/var/backups/salom-directory` without interrupting the running API. It is a manual backup helper; no recurring off-server backup is configured by this change.
