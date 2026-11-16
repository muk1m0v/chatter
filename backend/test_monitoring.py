"""PII-free runtime metric unit tests."""
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch

from fastapi.testclient import TestClient

import app
from monitoring import RuntimeMetrics, process_sample


class RuntimeMetricsTests(unittest.TestCase):
    def test_request_aggregation_keeps_only_status_and_latency(self):
        metrics = RuntimeMetrics(latency_sample_size=3)
        metrics.record_request("GET", 200, 8.0)
        metrics.record_request("POST", 429, 30.0)
        metrics.record_request("POST", 503, 12.0)

        requests, statuses, latencies = metrics.drain()
        self.assertEqual(requests, 3)
        self.assertEqual(statuses, {"2xx": 1, "4xx": 1, "5xx": 1})
        self.assertEqual(latencies, [8.0, 30.0, 12.0])
        self.assertEqual(metrics.drain(), (0, {}, []))

    def test_process_sample_is_safe_without_linux_procfs(self):
        rss_bytes, fd_count = process_sample()
        self.assertTrue(rss_bytes is None or rss_bytes > 0)
        self.assertTrue(fd_count is None or fd_count >= 0)

    def test_http_middleware_records_success_and_client_errors(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(app, "DB", Path(directory) / "monitor.sqlite3"), patch("app.PushDispatcher"):
            with TestClient(app.app) as client:
                self.assertEqual(client.get("/auth/config").status_code, 200)
                self.assertEqual(client.get("/route-not-found").status_code, 404)
                requests, statuses, latencies = app.app.state.runtime_metrics.drain()

        self.assertEqual(requests, 2)
        self.assertEqual(statuses, {"2xx": 1, "4xx": 1})
        self.assertEqual(len(latencies), 2)


if __name__ == "__main__":
    unittest.main()
