# Server readiness — 2026-11-13

Target: `94.237.62.77`. Successful SSH authentication using the existing local setup. This report contains configuration observations, not credentials.

| Check | Observed result |
| --- | --- |
| OS | Ubuntu 26.04 LTS, x86_64, KVM VM |
| CPU | 4 vCPUs, AMD EPYC 7542 host model |
| Memory | 7,888 MiB total; 4,256 MiB available at the initial sample |
| Swap | 2,047 MiB total; about 647 MiB occupied; no swap-in/out during the three-sample vmstat check |
| Root filesystem | 493 GiB reported, 453 GiB available, 5% used |
| Inodes | 1% used |
| Load | 0.08 / 0.04 / 0.01 at the initial sample; approximately 99–100% CPU idle in the short sample |
| Python | 3.14.4; venv, SSL and sqlite3 imports work |
| PostgreSQL | 18.6; accepting connections; bound to loopback |
| Redis | 8.0.5; PING returns PONG; bound to loopback |
| MySQL | Running and bound to loopback; no authenticated database query performed |
| Nginx | 1.28.3, active; current nginx -t succeeds |
| Failed systemd units | One transient historical nginx -t unit; current Nginx validation succeeds |
| Firewall | UFW active; OpenSSH and Nginx Full allowed for IPv4 and IPv6 |
| Listening web ports | 80 and 443 already used by Nginx |
| Other applications | Multiple existing application and worker services; they must remain isolated from Chatter |
| Node.js / Docker | Not installed; neither is inherently required for a Python/systemd deployment |
| Time synchronization | NTP synchronized; server timezone UTC |
| Updates | 69 package entries listed as upgradable; reboot-required marker present |
| Root directory ownership | `/` is UID 999 / GID 983, mode 755; UID 999 is the application account `aura`. `/etc`, `/usr`, `/var`, `/opt`, `/srv` and `/root` are root-owned. Requires review before deployment. No ownership was changed. |
| Chatter directories | `/srv/chatter`, `/opt/chatter`, `/var/www/chatter` were not present |
| Default HTTP host | A localhost request without an application hostname returned no HTTP response. Existing application domains were not tested end-to-end. |

## Assessment

The observed spare CPU, available memory, and disk space are sufficient to begin a small messenger backend alongside the existing services. This is a readiness assessment, not a concurrency, bandwidth, security, or availability benchmark. Calls, large media workloads, and user capacity require separate tests after implementation.

Before deployment: review and correct root-directory ownership, plan OS maintenance around existing services, select the Chatter domain and TLS configuration, and create an isolated service account, database, internal port, deployment directory, and off-server backup policy. No Chatter domain, database, backend endpoint, upload pipeline, or notification service has been deployed or tested.
