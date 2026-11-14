# Salom directory API

Purpose: make username lookup work across Android installations. This service is independent of the APK page and existing applications on the VPS.

| Endpoint (prefix `/salom/api`) | Authentication | Behavior |
| --- | --- | --- |
| GET `/health` | None | Database readiness |
| PUT `/profile` | Bearer installation key | Idempotent enrollment or own profile update; 409 for occupied username, 403 for another owner's ID |
| GET `/search?q=prefix` | Registered Bearer installation key | Up to 20 active public profiles, case-insensitive username prefix; optional @ |

Public response fields: id, name, username, is_active. No phone, email, token or key hash is returned. The server never receives the address book.

On the authorized VPS, create `/opt/salom-api/.venv` with Python 3.14 and install `requirements.lock`. Upload `app.py`, `requirements.txt`, `salom-api.service`, and `deploy.py` to `/tmp/salom-api-deploy`, then run its `deploy.py` as root. The script preserves existing Nginx routes and backups existing configuration/database. The service itself runs as `salom-api` with a private state directory and loopback-only Uvicorn.

`smoke_test.py` checks two independent identities over public HTTPS and removes only its own generated fixtures. `ui_probe.py create @username` checks a real target from an independent temporary account and publishes a clearly named QA fixture for Android UI testing; run `ui_probe.py cleanup` afterward. Never leave QA fixtures in the public directory.

`requirements.lock` records the installed versions. Runtime SQLite data and installation credentials are not committed to Git. Account recovery and verified phone identity are future work; update Android in place to preserve its installation credential.

`backup.py` creates a consistent root-private SQLite snapshot in `/var/backups/salom-directory` without interrupting the running API. It is a manual backup helper; no recurring off-server backup is configured by this change.
