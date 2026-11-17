# SALOM new VPS rollout

This runbook tracks the new Ubuntu 24.04 VPS setup. The old VPS remains the live production server until the new host passes the staging checks and a cutover is explicitly approved.

## Read-only baseline (2026-11-16)

- Ubuntu 24.04.5 LTS, 1 vCPU, 961 MiB RAM, 24 GiB root filesystem with about 22 GiB free.
- No swap configured. Python 3.12.3 is present.
- Only SSH is listening. Nginx, SALOM, PostgreSQL and Redis are not installed or active.
- UFW is inactive. Effective sshd settings allow root and password authentication.
- The login banner reports 47 available package updates, including 38 security updates.
- No failed systemd units were reported.
- `mukimov.site` currently resolves to the old production VPS, so the new host is not ready for certificate issuance or public cutover.

No server configuration was changed during this baseline check.

## Provisioning progress (2026-11-17)

- A 2 GiB `/swapfile` is active with mode `0600`, persisted in `/etc/fstab`; `vm.swappiness=10` is configured.
- UFW is enabled with default-deny inbound and only SSH (22), HTTP (80), and HTTPS (443) allowed. No database or Redis ports are exposed.
- `apt-get upgrade` completed without removals: 37 packages upgraded. The full-upgrade simulation shows four kernel metapackage upgrades and six new kernel image/header/tool packages, with no removals; three other updates are deferred by Ubuntu phasing. They have not been applied. The host reports a reboot-needed marker for `libc6`; do not reboot until admin access and a recovery path are confirmed.
- Nginx, PostgreSQL 16, Redis, fail2ban, Restic, `python3-venv`, and `needrestart` are installed. Nginx, PostgreSQL, Redis, and fail2ban are enabled and active.
- PostgreSQL listens on `127.0.0.1:5432`, with conservative 1 GiB settings (96 MiB shared buffers, 16 max connections, 2 MiB work memory). Redis listens only on loopback, uses a 64 MiB max-memory limit and `noeviction`; the app does not currently depend on Redis.
- Root/password SSH login is disabled. Public-key login to `salom-deploy` was verified after the change. Its account is locked and has no usable sudo rights, so privileged deployment work is paused pending recovery of controlled admin access through the DigitalOcean console. Do not re-enable password SSH; restore root public-key-only access or install the intended narrow sudoers wrapper from the console.
- Current measured footprint after package installation: 961 MiB RAM total, about 582 MiB available, 2 GiB swap with under 1 MiB used, and 19 GiB free on the 24 GiB root disk. These are idle-host readings, not load capacity.
- A read-only GitHub deploy key was generated under `/home/salom-deploy/.ssh`; its public half must be added as a read-only deploy key in the private repository before cloning. Its private half is not in Git.
- No SALOM release, user data, Firebase credential, production database, backup destination, TLS certificate, or DNS change has been installed/configured on the new host.

## Architecture decisions

- The backend supports PostgreSQL through `SALOM_DATABASE_URL`; production currently uses SQLite. `migrate_sqlite_to_postgres.py` remains test-only. A separate `migrate_sqlite_to_postgres_prod.py` now accepts only a consistent backup, requires the explicitly confirmed empty `salom_prod` database, verifies row counts/checksums, and rolls back its database transaction on error. It must still be rehearsed on the new host before any cutover.
- Redis is not currently used by the app. `RealtimeHub` is process-local and the service must remain at one Uvicorn worker. Do not install Redis until shared presence/pub-sub or multi-worker operation is implemented and measured.
- Preserve the SQLite database, media directory, Firebase Admin credential and download page/APKs. Keep credentials out of Git. The client `google-services.json` is not a substitute for the server credential.
- The current 1 GiB plan is constrained. Add up to 2 GiB of swap and measure the real steady-state footprint before production cutover. Swap is emergency headroom, not a RAM substitute. Do not claim capacity for load beyond measured results.
- The app and website can be staged by IP/Host header. Do not issue Let's Encrypt certificates until DNS points to the new host, and do not change DNS until explicit cutover approval.

## Prepared deployment assets

- `ops/deploy.sh` accepts a full main-branch commit SHA, refuses a dirty checkout, builds an immutable release from Git archive, installs pinned requirements, runs source preflight/compile checks, atomically switches `current`, restarts the API, and restores only the previous code symlink if the HTTP health check fails. It never migrates or rolls back a database.
- `ops/sudoers/salom-deploy` permits only the root-owned deployment wrapper. Validate the installed file with `visudo -cf`.
- `backend/salom-api.service` uses one worker, requires PostgreSQL, caps the pool at four by default, has a 384 MiB memory ceiling, and retains a 4096 soft descriptor limit. Measure actual usage before relaxing these bounds.
- `ops/postgres/salom.conf` keeps PostgreSQL local-only and uses a 96 MiB shared-buffer target with conservative connection and work-memory limits. It preserves PostgreSQL durability defaults.
- `ops/nginx/` provides HTTP/WebSocket proxy and sanitized access-log templates. It requires a separate TLS setup after DNS points to the new server.
- `ops/salom-healthcheck.py` plus its systemd service/timer emits host/API metrics and threshold warnings to journald; it never deletes data or logs message content.

## Deployment gates

1. Confirm SSH key login in a separate session, then disable root/password SSH; retain the working session until the new login is re-tested.
2. Install security updates without rebooting automatically. Configure UFW for SSH, HTTP and HTTPS before enabling it. Confirm provider firewall rules separately.
3. Add swap, create a locked `salom-api` runtime account and a restricted deploy account, and install only required packages.
4. Tune PostgreSQL for one CPU and low memory; cap API concurrency and connection-pool size. Keep PostgreSQL bound to localhost. Do not install Redis for the current architecture.
5. Create an online SQLite backup on the old host, verify its integrity, copy the backup and media over SSH, and preserve the original. Create the target PostgreSQL database separately and verify row counts/checksums before starting the new API.
6. Store `SALOM_DATABASE_URL` and Firebase Admin credentials in root-owned files readable only by the service group. Run the API as `salom-api`, one worker, on loopback; retain `LimitNOFILE` headroom and the connection-close fix.
7. Configure Nginx with WebSocket Upgrade headers, bounded timeouts/request sizes and log rotation. Keep access logs free of message contents and authorization headers.
8. Configure a lightweight health/metrics timer and bounded journald usage. No automatic deletion of user data or backups.
9. Use independent encrypted off-VPS storage for database/media backups and perform a restore test. No such storage was confirmed during the initial audit; backup readiness is therefore incomplete.
10. Run API, WebSocket, push, restart/reconnect and resource checks on the staged host. Keep the old server available for rollback. DNS/TLS cutover remains a separate approval gate.

## Current blockers

- SSH password access worked for the interactive audit, but later SSH/SCP attempts were rejected. Install and verify the existing public key before making further SSH policy changes.
- No off-VPS backup destination is configured.
- A preliminary online SQLite snapshot and attachments archive were created on the old VPS on 2026-11-17; SQLite integrity passed and SHA-256 hashes were recorded for both files. These remain on the same source host and live data can still change, so this is not the final cutover snapshot or an off-site backup.
- No deployment, database copy, firewall change, package update, DNS change or service restart has been performed on the new host.
- Release readiness requires a tested production-data migration path, staging WebSocket checks, a verified backup restore, SSH hardening and measurements under the 1 GiB limit.
