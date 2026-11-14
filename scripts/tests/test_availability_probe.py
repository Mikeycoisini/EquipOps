from concurrent.futures import ThreadPoolExecutor
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest


PROBE = Path(__file__).resolve().parents[1] / "availability_probe.py"


class HealthHandler(BaseHTTPRequestHandler):
    status = 200
    body = b'{"status":"UP"}'
    paths = []
    delay = 0

    def do_GET(self):
        self.paths.append(self.path)
        self.send_response(self.status)
        if self.status == 302:
            self.send_header("Location", "/must-not-follow")
        self.end_headers()
        if self.delay:
            time.sleep(self.delay)
        self.wfile.write(self.body)

    def log_message(self, *args):
        pass


class AvailabilityProbeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        HealthHandler.status = 200
        HealthHandler.body = b'{"status":"UP"}'
        HealthHandler.paths = []
        HealthHandler.delay = 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.state = str(Path(self.directory.name) / "state.sqlite")

    def command(self, operation):
        result = subprocess.run(
            [sys.executable, str(PROBE), operation, "--state", self.state,
             "--port", str(self.server.server_port)],
            check=True, capture_output=True, text=True, timeout=10,
        )
        return json.loads(result.stdout)

    def test_persisted_transition_requires_consecutive_samples_and_deduplicates(self):
        HealthHandler.status = 503
        HealthHandler.body = b'{"status":"DOWN","private":"test-secret"}'
        for _ in range(2):
            self.assertEqual("HEALTH_STATUS_DOWN", self.command("sample")["last_result"])
        self.assertEqual([], self.command("status")["events"])
        for _ in range(4):
            self.command("sample")
        self.assertEqual(["OPEN"], [event["kind"] for event in self.command("status")["events"]])

        HealthHandler.status = 200
        HealthHandler.body = b'{"status":"UP"}'
        self.command("sample")
        HealthHandler.body = b'{"status":"DOWN"}'
        self.command("sample")
        HealthHandler.body = b'{"status":"UP"}'
        self.command("sample")
        self.assertTrue(self.command("status")["state"]["incident_open"])
        self.command("sample")
        state = self.command("status")
        self.assertFalse(state["state"]["incident_open"])
        self.assertEqual(["OPEN", "RECOVERED"], [event["kind"] for event in state["events"]])
        self.assertNotIn("test-secret", json.dumps(state))

    def test_rejects_redirect_and_malformed_or_non_health_body(self):
        HealthHandler.status = 302
        self.assertEqual("HTTP_UNEXPECTED_STATUS", self.command("sample")["last_result"])
        self.assertEqual(["/actuator/health"], HealthHandler.paths)
        HealthHandler.status = 200
        for body in (b'not json', b'[]', b'{"other":"UP"}', b" " * 8193):
            HealthHandler.body = body
            self.assertEqual("HEALTH_INVALID_RESPONSE", self.command("sample")["last_result"])

    def test_status_does_not_create_a_state_file(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.command("status")
        self.assertFalse(Path(self.state).exists())

    def test_parallel_sampling_creates_one_open_event(self):
        HealthHandler.status = 503
        HealthHandler.body = b'{"status":"DOWN"}'
        with ThreadPoolExecutor(max_workers=6) as workers:
            self.assertEqual(["HEALTH_STATUS_DOWN"] * 6,
                             [item["last_result"] for item in workers.map(self.command, ["sample"] * 6)])
        self.assertEqual(["OPEN"], [event["kind"] for event in self.command("status")["events"]])

    def test_response_timeout_is_not_a_successful_sample(self):
        HealthHandler.delay = 3
        self.assertEqual("TIMEOUT", self.command("sample")["last_result"])
        self.assertEqual([], self.command("status")["events"])

    def test_transport_failure_is_recorded_and_other_port_cannot_reuse_state(self):
        self.command("sample")
        result = subprocess.run(
            [sys.executable, str(PROBE), "status", "--state", self.state,
             "--port", str(1 if self.server.server_port != 1 else 2)],
            capture_output=True, text=True, timeout=10,
        )
        self.assertNotEqual(0, result.returncode)
        self.server.shutdown()
        self.server.server_close()
        self.assertEqual("CONNECT_FAILED", self.command("sample")["last_result"])


if __name__ == "__main__":
    unittest.main()
