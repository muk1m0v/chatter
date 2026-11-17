#!/usr/bin/env python3
"""Small, local-only SALOM host health sampler; logs metrics, never user data."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen


STATE = Path("/var/lib/salom-monitor/swap-used")
JOURNAL = "/usr/bin/journalctl"
SYSTEMCTL = "/usr/bin/systemctl"


def run(args, timeout=4):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
        return result.stdout.strip() if result.returncode == 0 else ""
    except (OSError, subprocess.TimeoutExpired):
        return ""


def memory_sample():
    values = {}
    for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
        key, _, raw = line.partition(":")
        if key in {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}:
            values[key] = int(raw.split()[0]) * 1024
    total = values.get("MemTotal", 0)
    available = values.get("MemAvailable", 0)
    swap_total = values.get("SwapTotal", 0)
    swap_free = values.get("SwapFree", 0)
    return {
        "total_bytes": total,
        "available_bytes": available,
        "used_percent": round(100 * (total - available) / total, 1) if total else None,
        "swap_total_bytes": swap_total,
        "swap_used_bytes": max(0, swap_total - swap_free),
    }


def service_state(name):
    return run([SYSTEMCTL, "is-active", name]) or "unknown"


def process_metrics():
    pid = run([SYSTEMCTL, "show", "salom-api", "--property=MainPID", "--value"])
    restarts = run([SYSTEMCTL, "show", "salom-api", "--property=NRestarts", "--value"])
    fd_limit = run([SYSTEMCTL, "show", "salom-api", "--property=LimitNOFILE", "--value"])
    fd_count = None
    try:
        if pid.isdigit() and int(pid) > 0:
            fd_count = len(list((Path("/proc") / pid / "fd").iterdir()))
    except OSError:
        pass
    return fd_count, int(fd_limit) if fd_limit.isdigit() else None, int(restarts) if restarts.isdigit() else None


def recent_logs():
    api = run([JOURNAL, "-u", "salom-api", "--since", "10 minutes ago", "--no-pager", "-o", "cat"], timeout=8)
    kernel = run([JOURNAL, "-k", "--since", "10 minutes ago", "--no-pager", "-o", "cat"], timeout=8)
    five_xx = sum(int(value) for value in re.findall(r"'5xx':\s*(\d+)", api))
    db_errors = sum(bool(re.search(r"\b(?:OperationalError|DatabaseError|sqlite3\.|psycopg\.)", line, re.I))
                    for line in api.splitlines())
    oom_events = sum(bool(re.search(r"out of memory|oom-kill|killed process", line, re.I))
                     for line in kernel.splitlines())
    ws_values = re.findall(r"websocket_connections=(-?\d+)", api)
    return five_xx, db_errors, oom_events, int(ws_values[-1]) if ws_values else None


def save_state(name, value):
    STATE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        state_path = STATE.parent / name
        previous = tuple(int(part) for part in state_path.read_text(encoding="ascii").split())
    except (OSError, ValueError):
        previous = None
    (STATE.parent / name).write_text(" ".join(str(part) for part in value), encoding="ascii")
    (STATE.parent / name).chmod(0o600)
    return previous


def cpu_sample():
    fields = [int(value) for value in Path("/proc/stat").read_text(encoding="ascii").splitlines()[0].split()[1:]]
    total = sum(fields)
    busy = total - fields[3] - (fields[4] if len(fields) > 4 else 0)
    previous = save_state("cpu-stat", (busy, total))
    if previous and len(previous) == 2 and total > previous[1]:
        return round(100 * max(0, busy - previous[0]) / (total - previous[1]), 1)
    return None


def postgres_database_size():
    value = run(["/usr/sbin/runuser", "-u", "postgres", "--", "/usr/bin/psql", "-d", "salom_prod",
                 "-A", "-t", "-c", "SELECT pg_database_size(current_database())"], timeout=5)
    return int(value) if value.isdigit() else None


def main():
    mem = memory_sample()
    disk = shutil.disk_usage("/")
    disk_percent = round(100 * disk.used / disk.total, 1)
    load = os.getloadavg()[0] if hasattr(os, "getloadavg") else None
    cpu_percent = cpu_sample()
    fd_count, fd_limit, restarts = process_metrics()
    services = {name: service_state(name) for name in ("salom-api", "postgresql", "nginx")}
    api_ms = None
    try:
        started = time.monotonic()
        with urlopen("http://127.0.0.1:18731/health", timeout=3) as response:
            if response.status == 200:
                api_ms = round((time.monotonic() - started) * 1000, 1)
    except (OSError, URLError, TimeoutError):
        pass
    five_xx, db_errors, oom_events, ws_count = recent_logs()
    swap_previous = save_state("swap-used", (mem["swap_used_bytes"],))
    nginx_log_bytes = 0
    try:
        nginx_log_bytes = sum(path.stat().st_size for path in Path("/var/log/nginx").glob("salom-*.log"))
    except OSError:
        pass
    journal_usage = run([JOURNAL, "--disk-usage"])

    metrics = {
        "time_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "cpu_load_1m": load,
        "cpu_busy_percent": cpu_percent,
        "memory": mem,
        "disk": {"total_bytes": disk.total, "free_bytes": disk.free, "used_percent": disk_percent},
        "process_count": sum(path.name.isdigit() for path in Path("/proc").iterdir()),
        "service": services,
        "api_health_ms": api_ms,
        "api_restarts": restarts,
        "api_open_fds": fd_count,
        "api_nofile_limit": fd_limit,
        "active_websockets": ws_count,
        "postgres_database_bytes": postgres_database_size(),
        "recent_http_5xx": five_xx,
        "recent_db_errors": db_errors,
        "recent_oom_events": oom_events,
        "nginx_log_bytes": nginx_log_bytes,
        "journal_usage": journal_usage,
    }
    warnings = []
    if mem["used_percent"] is not None and mem["used_percent"] > 85:
        warnings.append("RAM usage above 85%")
    if mem["available_bytes"] < 128 * 1024 * 1024:
        warnings.append("critically low available RAM")
    if disk_percent > 80:
        warnings.append("root filesystem above 80%")
    if swap_previous and mem["swap_used_bytes"] - swap_previous[0] > 128 * 1024 * 1024:
        warnings.append("swap grew by more than 128 MiB since last sample")
    if fd_count is not None and fd_count > 2048:
        warnings.append("SALOM open file descriptors above 2048")
    if fd_count is not None and fd_limit and fd_count > fd_limit * 0.75:
        warnings.append("SALOM is using over 75% of its file descriptor limit")
    if any(state != "active" for state in services.values()):
        warnings.append("one or more required services are inactive")
    if api_ms is None:
        warnings.append("local API healthcheck failed")
    if restarts is not None and restarts >= 3:
        warnings.append("SALOM has restarted at least three times")
    if five_xx:
        warnings.append(f"recent HTTP 5xx count: {five_xx}")
    if db_errors:
        warnings.append(f"recent database error lines: {db_errors}")
    if oom_events:
        warnings.append(f"recent kernel OOM events: {oom_events}")

    print("salom_host_metrics " + json.dumps(metrics, separators=(",", ":"), sort_keys=True))
    for warning in warnings:
        print("salom_host_warning " + warning)


if __name__ == "__main__":
    main()
