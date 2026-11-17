#!/usr/bin/env bash
set -Eeuo pipefail

# Root-owned wrapper, invoked by the restricted salom-deploy sudo rule.
# Database migrations are intentionally never run by this script.
APP_ROOT=/opt/salom-api
REPO=/home/salom-deploy/chatter
RELEASES="$APP_ROOT/releases"
SERVICE=salom-api
STAGING=""
PREVIOUS=""
SWITCHED=0

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "must run as root through the restricted sudo rule"
[[ $# -eq 1 ]] || fail "usage: salom-deploy <40-character-main-commit-sha>"
TARGET=$1
[[ $TARGET =~ ^[0-9a-f]{40}$ ]] || fail "commit must be a full lowercase SHA"
[[ -d "$REPO/.git" && -d "$RELEASES" ]] || fail "deployment directories are not initialized"

exec 9>/run/lock/salom-deploy.lock
/usr/bin/flock -n 9 || fail "another deployment is running"

if /usr/sbin/runuser -u salom-deploy -- /usr/bin/git -C "$REPO" status --porcelain | /usr/bin/grep -q .; then
    fail "Git checkout is dirty; refusing to deploy over local changes"
fi
/usr/sbin/runuser -u salom-deploy -- /usr/bin/git -C "$REPO" fetch --quiet origin main
/usr/sbin/runuser -u salom-deploy -- /usr/bin/git -C "$REPO" cat-file -e "$TARGET^{commit}" 2>/dev/null || fail "commit is not available in the private checkout"
/usr/sbin/runuser -u salom-deploy -- /usr/bin/git -C "$REPO" merge-base --is-ancestor "$TARGET" origin/main || fail "commit is not in origin/main"

rollback_and_cleanup() {
    code=$?
    trap - EXIT
    if [[ -n "$STAGING" && -d "$STAGING" && "$STAGING" == "$RELEASES/.staging-$TARGET-"* ]]; then
        /usr/bin/rm -rf -- "$STAGING"
    fi
    if [[ $code -ne 0 && $SWITCHED -eq 1 && -n "$PREVIOUS" && -d "$PREVIOUS" ]]; then
        log "health check failed; restoring application release only"
        ROLLBACK_LINK="$APP_ROOT/.rollback-$$"
        /usr/bin/ln -s "$PREVIOUS" "$ROLLBACK_LINK"
        /usr/bin/mv -Tf "$ROLLBACK_LINK" "$APP_ROOT/current"
        /usr/bin/systemctl restart "$SERVICE" || true
    fi
    exit "$code"
}
trap rollback_and_cleanup EXIT

RELEASE="$RELEASES/$TARGET"
if [[ ! -d "$RELEASE" ]]; then
    STAGING="$RELEASES/.staging-$TARGET-$$"
    /usr/bin/install -d -o salom-deploy -g salom-api -m 0750 "$STAGING"
    /usr/sbin/runuser -u salom-deploy -- /usr/bin/git -C "$REPO" archive "$TARGET" backend ops \
        | /usr/sbin/runuser -u salom-deploy -- /usr/bin/tar -x -C "$STAGING"
    /usr/sbin/runuser -u salom-deploy -- /usr/bin/python3 -m venv "$STAGING/.venv"
    /usr/sbin/runuser -u salom-deploy -- "$STAGING/.venv/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$STAGING/backend/requirements.lock"
    /usr/sbin/runuser -u salom-deploy -- "$STAGING/.venv/bin/python" -m compileall -q "$STAGING/backend" "$STAGING/ops/salom-healthcheck.py"
    /usr/sbin/runuser -u salom-deploy -- "$STAGING/.venv/bin/python" "$STAGING/backend/deploy.py" --source "$STAGING/backend"
    /usr/bin/chown -R root:salom-api "$STAGING"
    /usr/bin/find "$STAGING" -type d -exec /usr/bin/chmod 0750 {} +
    /usr/bin/find "$STAGING" -type f -exec /usr/bin/chmod 0640 {} +
    /usr/bin/find "$STAGING/.venv/bin" -type f -exec /usr/bin/chmod 0750 {} +
    /usr/bin/mv "$STAGING" "$RELEASE"
    STAGING=""
fi

CURRENT="$APP_ROOT/current"
if [[ -L "$CURRENT" ]]; then PREVIOUS=$(/usr/bin/readlink -f "$CURRENT"); fi
NEXT="$APP_ROOT/.current-$TARGET"
[[ ! -e "$NEXT" && ! -L "$NEXT" ]] || fail "stale switch path exists: $NEXT"
/usr/bin/ln -s "$RELEASE" "$NEXT"
/usr/bin/mv -Tf "$NEXT" "$CURRENT"
SWITCHED=1

/usr/bin/systemctl restart "$SERVICE"
/usr/bin/systemctl is-active --quiet "$SERVICE" || fail "SALOM systemd unit is not active"
"$CURRENT/.venv/bin/python" -c 'from urllib.request import urlopen; response=urlopen("http://127.0.0.1:18731/health", timeout=3); raise SystemExit(0 if response.status == 200 else 1)' \
    || fail "local API health check failed"

trap - EXIT
log "deployed commit $TARGET; API health check passed; database was not modified"
