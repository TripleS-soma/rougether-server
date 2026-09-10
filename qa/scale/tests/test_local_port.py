import importlib.util
from pathlib import Path
import socket
import sys
import unittest


HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location("run_local", HERE / "run-local.py")
run_local = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run_local)


class PortAvailabilityTests(unittest.TestCase):
    def test_live_listener_is_rejected_and_remains_available(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            with self.assertRaises(OSError):
                run_local.check_port_available(port)
            with socket.create_connection(("127.0.0.1", port)) as client:
                connection, _ = listener.accept()
                connection.close()
                self.assertEqual(client.recv(1), b"")

    def test_port_is_reusable_after_server_closes_connection(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            with socket.create_connection(("127.0.0.1", port)) as client:
                connection, _ = listener.accept()
                connection.close()
                self.assertEqual(client.recv(1), b"")
        run_local.check_port_available(port)


if __name__ == "__main__":
    unittest.main()
