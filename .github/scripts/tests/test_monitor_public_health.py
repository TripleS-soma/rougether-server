import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError, URLError

SCRIPT = Path(__file__).resolve().parents[1] / "monitor_public_health.py"
STOP_SCRIPT = SCRIPT.with_name("stop-public-health-monitor.sh")
spec = importlib.util.spec_from_file_location("monitor_public_health", SCRIPT)
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def log_message(self, *_args):
        pass


class MonitorPublicHealthTest(unittest.TestCase):
    def test_preserves_wrapped_and_direct_network_causes(self):
        cases = [
            (URLError(socket.gaierror(-2, "Name or service not known")), "gaierror", -2),
            (URLError(ConnectionRefusedError(61, "Connection refused")), "ConnectionRefusedError", 61),
            (URLError(ssl.SSLError(1, "TLS handshake failed")), "SSLError", 1),
            (TimeoutError(60, "Operation timed out"), "TimeoutError", 60),
            (ConnectionResetError(54, "Connection reset by peer"), "ConnectionResetError", 54),
        ]
        for error, reason_type, number in cases:
            with self.subTest(reason_type=reason_type), patch.object(health, "urlopen", side_effect=error):
                row = health.probe(("api", "https://example.test/health"), 1)
                self.assertIsNone(row["code"])
                self.assertEqual(row["reasonType"], reason_type)
                self.assertEqual(row["errno"], number)
                self.assertTrue(row["reason"])
                self.assertTrue(row["errorMessage"])
                self.assertEqual(row["failureStage"], "open_response")

    def test_http_error_is_separate_from_connection_failure(self):
        error = HTTPError("https://example.test/health", 503, "Service Unavailable", {"x-amz-cf-id": "request-id"}, None)
        with patch.object(health, "urlopen", side_effect=error):
            row = health.probe(("api", "https://example.test/health"), 1)
        self.assertEqual(row["code"], 503)
        self.assertEqual(row["reason"], "Service Unavailable")
        self.assertIsNone(row["errno"])
        self.assertEqual(row["cloudFrontRequestId"], "request-id")
        self.assertEqual(row["failureStage"], "http_response")

    def test_body_failure_keeps_received_http_status(self):
        from unittest.mock import MagicMock
        response = MagicMock(status=200)
        response.__enter__.return_value = response
        response.read.return_value = b"not-json"
        response.headers = {}
        with patch.object(health, "urlopen", return_value=response):
            row = health.probe(("api", "https://example.test/health"), 1)
        self.assertEqual(row["code"], 200)
        self.assertEqual(row["failureStage"], "parse_body")
        self.assertFalse(row["healthy"])
        self.assertEqual(row["error"], "JSONDecodeError")

    def test_failure_does_not_stop_other_targets_and_summary_matches_records(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stop = threading.Event()
            def probe(endpoint, _timeout):
                name, _url = endpoint
                if name == "healthy":
                    stop.set()
                    return {"service": name, "code": 200, "healthy": True}
                return {"service": name, "code": None, "healthy": False, "errno": -2}
            with patch.object(health, "probe", side_effect=probe):
                health.monitor([("failed", "https://a.test"), ("healthy", "https://b.test")],
                               root / "health.jsonl", root / "summary.json", root / "stop", 10, .01, 1, stop)
            rows = [json.loads(line) for line in (root / "health.jsonl").read_text().splitlines()]
            counts = json.loads((root / "summary.json").read_text())["services"]
            self.assertEqual(len(rows), 2)
            self.assertEqual(counts["failed"]["connectionFailures"], 1)
            self.assertEqual(counts["healthy"]["healthy"], 1)

    def test_cli_records_real_http_and_exits_on_stop_file(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                args = [sys.executable, str(SCRIPT), "--target", f"api=http://127.0.0.1:{server.server_port}/health",
                        "--output", str(root / "health.jsonl"), "--summary", str(root / "summary.json"),
                        "--stop-file", str(root / "stop"), "--duration", "20", "--interval", ".05"]
                process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        if (root / "health.jsonl").exists() and (root / "health.jsonl").stat().st_size:
                            break
                        time.sleep(.02)
                    stopped = subprocess.run(["bash", str(STOP_SCRIPT), str(process.pid), str(root / "stop")],
                                             capture_output=True, text=True, timeout=8)
                    self.assertEqual(stopped.returncode, 0, stopped.stdout + stopped.stderr)
                    stdout, stderr = process.communicate(timeout=5)
                    self.assertEqual(process.returncode, 0, stdout + stderr)
                    rows = [json.loads(line) for line in (root / "health.jsonl").read_text().splitlines()]
                    self.assertTrue(rows)
                    self.assertTrue(all(row["healthy"] for row in rows))
                    self.assertEqual(json.loads((root / "summary.json").read_text())["services"]["api"]["healthy"], len(rows))
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.communicate()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_shutdown_forces_stuck_monitor_to_exit_before_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.Popen([sys.executable, "-c",
                "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); print('ready',flush=True); time.sleep(60)"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual(process.stdout.readline().strip(), "ready")
                result = subprocess.run(["bash", str(STOP_SCRIPT), str(process.pid), str(Path(directory) / "stop")],
                                        env={**os.environ, "HEALTH_MONITOR_STOP_GRACE_SECONDS": "0",
                                             "HEALTH_MONITOR_TERM_GRACE_SECONDS": "0"},
                                        capture_output=True, text=True, timeout=8)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                process.communicate(timeout=3)
                self.assertLess(process.returncode, 0)
                self.assertIn("forcing exit", result.stdout)
                self.assertTrue((Path(directory) / "stop").exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()

    def test_rejects_urls_that_could_expose_credentials(self):
        for url in ["api=https://user:secret@example.test/health", "api=https://example.test/health?token=secret",
                    "api=file:///etc/passwd", "api=https://example.test/health#secret"]:
            with self.subTest(url=url), self.assertRaises(Exception):
                health.target(url)


if __name__ == "__main__":
    unittest.main()
