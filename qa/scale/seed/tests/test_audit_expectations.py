import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "qa" / "scale" / "seed"))
AUDIT_RUNNER_PATH = ROOT / "qa" / "scale" / "seed" / "audit.py"
runner_spec = importlib.util.spec_from_file_location("audit_runner", AUDIT_RUNNER_PATH)
audit_runner = importlib.util.module_from_spec(runner_spec)
runner_spec.loader.exec_module(audit_runner)


FIXTURE = {
    "databaseName": "rougether_scale",
    "date": "2026-09-09",
    "userStartId": 900000000,
    "usersCount": 2,
    "todoStartId": 910000000,
    "todoCount": 20,
    "reward": {"todoCompleteAmount": 10, "dailyCap": 50},
}


class AuditExpectationsTest(unittest.TestCase):
    def test_expected_rewards_are_per_user_capped_from_planned_prefix(self):
        expected = audit_runner.expected_from_prefix(FIXTURE, 12)

        self.assertEqual(expected["completionsByUser"], {900000000: 6, 900000001: 6})
        self.assertEqual(expected["rewardsByUser"], {900000000: 50, 900000001: 50})

    def test_ambiguous_or_dropped_plan_is_not_passed(self):
        expected_unique, violations = audit_runner.planned_unique_completions(
            FIXTURE,
            {"config": {"scenario": "write", "iterations": 12, "droppedIterations": 1, "completionSuccess": 11}},
        )
        self.assertIsNone(expected_unique)
        self.assertEqual(violations, ["run had dropped iterations: 1"])

    def test_snapshot_audit_reports_swapped_history_owner(self):
        expected = audit_runner.expected_from_prefix(FIXTURE, 1)
        snapshot = {
            "scalars": {
                "meta_database_ok": 1,
                "todo_total": 20,
                "owner_mismatch": 0,
                "duplicate_history": 0,
                "history_swaps": 1,
            },
            "completed": {900000000: (1, 10)},
            "history": {900000001: (1, 10)},
            "wallet": {900000000: (1, 10), 900000001: (1, 0)},
            "room": {900000000: (1, 10), 900000001: (1, 0)},
        }

        errors = audit_runner.audit_snapshot(FIXTURE, expected, snapshot)

        self.assertIn("wallet history user/source owner swaps: 1", errors)
        self.assertIn("user 900000000 wallet history count expected 1, actual 0", errors)


if __name__ == "__main__":
    unittest.main()
