# SALOM new VPS rollout

This runbook tracks the new Ubuntu 24.04 VPS setup. The old VPS remains the live production server until the new host passes the staging checks and a cutover is explicitly approved.

## Read-only baseline (2026-11-16)

- Ubuntu 24.04.5 LTS, 1 vCPU, 961 MiB RAM, 24 GiB root filesystem with about 22 GiB free.
- No swap configured. Python 3.12.3 is present.
- Only SSH is listening. Nginx, SALOM, PostgreSQL and Redis are not installed or active.
- UFW is inactive. Effective sshd settings allow root and password authentication.
- The login banner reports 47 available package updates, including 38 security updates.
- No failed systemd units were reported.

No server configuration was changed during this baseline check.

## Architecture decisions

- The backend supports PostgreSQL through `SALOM_DATABASE_URL`; production currently uses SQLite. The checked-in SQLite-to-PostgreSQL script is test-only and refuses production database names. Do not point it at production. A separately reviewed production migration/verification path is required before moving the live database.
- Redis is not currently used by the app. `RealtimeHub` is process-local and the service must remain at one Uvicorn worker. Do not install Redis until shared presence/pub-sub or multi-worker operation is implemented and measured.
- Preserve the SQLite database, media directory, Firebase Admin credential and download page/APKs. Keep credentials out of Git. The client `google-services.json` is not a substitute for the server credential.
- The current 1 GiB plan is constrained. Add up to 2 GiB of swap and measure the real steady-state footprint before production cutover. Swap is emergency headroom, not a RAM substitute. Do not claim capacity for load beyond measured results.
- The app and website can be staged by IP/Host header. Do not issue Let's Encrypt certificates until DNS points to the new host, and do not change DNS until explicit cutover approval.

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

- SSH password access worked for the interactive audit, but a later key upload attempt was rejected. Install and verify the existing public key before making further SSH policy changes.
- No off-VPS backup destination is configured.
- No deployment, database copy, firewall change, package update, DNS change or service restart has been performed on the new host.
- Release readiness requires a tested production-data migration path, staging WebSocket checks, a verified backup restore, SSH hardening and measurements under the 1 GiB limit.
