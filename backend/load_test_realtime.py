"""Local-only concurrent WebSocket delivery load test; never targets production."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import ExitStack
import hashlib
import json
import secrets
import statistics
import tempfile
import time
import tracemalloc
import unittest.mock
import uuid
from pathlib import Path

import app
import media
from realtime import RealtimeHub
from starlette.testclient import TestClient


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int((len(ordered) - 1) * fraction + 0.999999)))]


def receive_with_timeout(socket, timeout: float) -> dict:
    future = socket.portal.start_task_soon(socket._send_rx.receive)
    try:
        event = future.result(timeout=timeout)
    except Exception:
        future.cancel()
        raise
    socket._raise_on_close(event)
    return json.loads(event["text"])


def run_stage(client_count: int, timeout: float) -> dict:
    with tempfile.TemporaryDirectory(prefix=f"salom-realtime-{client_count}-") as directory:
        root = Path(directory)
        previous_db, previous_media = app.DB, media.ROOT
        app.DB = root / "stage.sqlite3"
        media.ROOT = root / "media"
        push_patch = unittest.mock.patch("app.PushDispatcher")
        hub_patch = unittest.mock.patch("app.RealtimeHub", side_effect=lambda: RealtimeHub(per_user_limit=2, total_limit=client_count + 8))
        push_patch.start()
        hub_patch.start()
        tokens = [secrets.token_urlsafe(36) for _ in range(client_count)]
        profile_ids = [str(uuid.uuid4()) for _ in range(client_count)]
        now = time.time()
        try:
            app.initialize()
            with app.connect() as db:
                for index, (profile_id, token) in enumerate(zip(profile_ids, tokens)):
                    username = f"load{index:04d}"
                    db.execute(
                        "INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)",
                        (profile_id, hashlib.sha256(token.encode()).hexdigest(), f"Load {index}", username, username, now, now),
                    )

            latencies_ms = []
            failures = []
            total_started = time.perf_counter()
            cpu_started = time.process_time()
            tracemalloc.start()
            with TestClient(app.app) as client, ExitStack() as stack:
                sockets = []
                for index in range(client_count):
                    peer_id = profile_ids[(index - 1) % client_count]
                    socket = stack.enter_context(client.websocket_connect(
                        "/ws", headers={"Authorization": "Bearer " + tokens[index]},
                    ))
                    if socket.receive_json().get("type") != "ready":
                        raise RuntimeError("WebSocket did not become ready")
                    socket.send_json({"type": "subscribe", "peer_id": peer_id})
                    if socket.receive_json().get("type") != "subscribed":
                        raise RuntimeError("WebSocket subscription was rejected")
                    socket.receive_json()  # Initial presence snapshot.
                    sockets.append(socket)

                def send_and_receive(sender_index: int) -> tuple[float, str | None]:
                    recipient_index = (sender_index + 1) % client_count
                    start = time.perf_counter()
                    response = client.post(
                        "/messages",
                        headers={"Authorization": "Bearer " + tokens[sender_index]},
                        json={
                            "recipient_id": profile_ids[recipient_index],
                            "client_id": str(uuid.uuid4()),
                            "text": f"load-{client_count}-{sender_index}",
                        },
                    )
                    if response.status_code != 200:
                        return 0.0, f"HTTP {response.status_code}"
                    event = receive_with_timeout(sockets[recipient_index], timeout)
                    elapsed = (time.perf_counter() - start) * 1000
                    message = event.get("message", {})
                    if event.get("type") != "message" or message.get("text") != f"load-{client_count}-{sender_index}":
                        return elapsed, "unexpected WebSocket event"
                    return elapsed, None

                with ThreadPoolExecutor(max_workers=min(client_count, 32)) as executor:
                    futures = [executor.submit(send_and_receive, index) for index in range(client_count)]
                    for future in as_completed(futures):
                        try:
                            latency, error = future.result(timeout=timeout + 2)
                            if error:
                                failures.append(error)
                            else:
                                latencies_ms.append(latency)
                        except Exception as error:
                            failures.append(f"{type(error).__name__}: {error}")
            _, peak_python_bytes = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            cpu_seconds = time.process_time() - cpu_started
            wall_seconds = time.perf_counter() - total_started
            return {
                "clients": client_count,
                "delivered": len(latencies_ms),
                "errors": len(failures),
                "error_examples": failures[:5],
                "p50_delivery_ms": round(statistics.median(latencies_ms), 2) if latencies_ms else None,
                "p95_delivery_ms": round(percentile(latencies_ms, 0.95), 2) if latencies_ms else None,
                "throughput_messages_per_second": round(len(latencies_ms) / wall_seconds, 2) if wall_seconds else 0,
                "stage_seconds": round(wall_seconds, 2),
                "process_cpu_seconds": round(cpu_seconds, 3),
                "peak_python_traced_mib": round(peak_python_bytes / (1024 * 1024), 2),
            }
        finally:
            if tracemalloc.is_tracing():
                tracemalloc.stop()
            hub_patch.stop()
            push_patch.stop()
            app.DB, media.ROOT = previous_db, previous_media


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clients", type=int, nargs="+", choices=(25, 50, 100), default=(25, 50, 100))
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    report = {"environment": "local temporary SQLite + in-process FastAPI/WebSocket", "results": []}
    for count in args.clients:
        report["results"].append(run_stage(count, args.timeout))
    print(json.dumps(report, indent=2))
    return 0 if all(stage["errors"] == 0 for stage in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
