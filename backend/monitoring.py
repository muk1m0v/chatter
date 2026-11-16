"""PII-free process and request health summaries for journald."""
from collections import Counter, deque
import logging
import os
from pathlib import Path
import threading
import time


log = logging.getLogger("uvicorn.error")


class RuntimeMetrics:
    def __init__(self, latency_sample_size: int = 512):
        self._lock = threading.Lock()
        self._requests = 0
        self._statuses: Counter[str] = Counter()
        self._latencies_ms = deque(maxlen=latency_sample_size)

    def record_request(self, method: str, status: int, elapsed_ms: float) -> None:
        status_group = f"{status // 100}xx"
        with self._lock:
            self._requests += 1
            self._statuses[status_group] += 1
            self._latencies_ms.append(max(0.0, elapsed_ms))

    def drain(self) -> tuple[int, dict[str, int], list[float]]:
        with self._lock:
            requests = self._requests
            statuses = dict(self._statuses)
            latencies = list(self._latencies_ms)
            self._requests = 0
            self._statuses.clear()
            self._latencies_ms.clear()
        return requests, statuses, latencies


def process_sample() -> tuple[int | None, int | None]:
    rss_bytes = None
    fd_count = None
    try:
        for line in Path("/proc/self/status").read_text(encoding="ascii").splitlines():
            if line.startswith("VmRSS:"):
                rss_bytes = int(line.split()[1]) * 1024
                break
    except (OSError, ValueError, IndexError):
        pass
    try:
        fd_count = len(os.listdir("/proc/self/fd"))
    except OSError:
        pass
    return rss_bytes, fd_count


class RuntimeReporter:
    def __init__(self, metrics: RuntimeMetrics, websocket_count, interval_seconds: float = 60.0):
        self.metrics = metrics
        self.websocket_count = websocket_count
        self.interval_seconds = interval_seconds
        self.stop_event = threading.Event()
        self.thread = None

    def start(self) -> None:
        self.thread = threading.Thread(target=self._run, name="salom-runtime-metrics", daemon=True)
        self.thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=max(1.0, self.interval_seconds + 1.0))

    def _run(self) -> None:
        previous_cpu = time.process_time()
        previous_wall = time.monotonic()
        while not self.stop_event.wait(self.interval_seconds):
            cpu_now = time.process_time()
            wall_now = time.monotonic()
            cpu_percent = 100 * (cpu_now - previous_cpu) / max(wall_now - previous_wall, 0.001)
            previous_cpu, previous_wall = cpu_now, wall_now
            requests, statuses, latencies = self.metrics.drain()
            rss_bytes, fd_count = process_sample()
            p95_ms = sorted(latencies)[max(0, int((len(latencies) - 1) * 0.95 + 0.999999))] if latencies else 0.0
            try:
                websocket_count = int(self.websocket_count())
            except Exception:
                websocket_count = -1
            log.info(
                "runtime requests=%d status=%s p95_ms=%.1f cpu_percent=%.1f rss_bytes=%s fd_count=%s websocket_connections=%d",
                requests, statuses, p95_ms, cpu_percent, rss_bytes, fd_count, websocket_count,
            )
