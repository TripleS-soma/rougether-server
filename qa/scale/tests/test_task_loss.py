from pathlib import Path
import importlib
import json
import os
import sys
import unittest
from unittest.mock import Mock, patch

scale = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(scale / "failover"), str(scale / "seed"), str(scale)]
workload = importlib.import_module("workload")


class TaskLossContractTest(unittest.TestCase):
    def send(self, status, body, attempt=1):
        conn = Mock()
        conn.getresponse.return_value.status = status
        conn.getresponse.return_value.read.return_value = json.dumps(body).encode()
        with (
            patch.dict(
                os.environ, {"BASE_URL": "http://qa.invalid", "QA_JWT": "test" * 10}
            ),
            patch.object(workload.local, "conn", conn, create=True),
        ):
            return workload.request("complete", 10, 20, 4, attempt)

    def test_completion_requires_created_and_correct_reward(self):
        body = {
            "id": 20,
            "status": "COMPLETED",
            "completedAt": "now",
            "rewardAmount": 10,
        }
        self.assertTrue(self.send(201, body)["ok"])
        self.assertFalse(self.send(200, body)["ok"])
        self.assertFalse(self.send(201, dict(body, rewardAmount=0))["ok"])
        self.assertFalse(self.send(201, [])["ok"])

    def test_only_documented_conflict_is_a_duplicate(self):
        self.assertTrue(
            self.send(409, {"code": "TODO_ALREADY_COMPLETED"}, 2)["duplicate"]
        )
        self.assertFalse(self.send(409, {"code": "ANOTHER_REASON"}, 2)["duplicate"])
