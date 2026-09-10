import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import index_experiment as experiment
from seed import audit


FIXTURE = {"databaseName": "rougether_scale", "date": "2026-09-10", "usersCount": 2,
           "userStartId": 900000000, "todoStartId": 910000000, "todoCount": 20,
           "reward": {"todoCompleteAmount": 10, "dailyCap": 50}}


def evidence(iterations=12):
    cycles, tail = divmod(iterations, 5)
    endpoints = {"me": cycles * 2 + int(tail >= 1) + int(tail >= 3),
                 "today": cycles * 2 + int(tail >= 2) + int(tail >= 4), "complete": cycles}
    counts = {"iterations": iterations, "sent_requests": iterations, "business_success": iterations,
              "completion_success": cycles, "rejected_requests": 0, "expected_conflicts": 0,
              "dropped_iterations": 10}
    counts.update({f"business_success{{endpoint:{key}}}": value for key, value in endpoints.items()})
    summary = {"profile": "mixed", "metrics": {key: {"count": value} for key, value in counts.items()}}
    manifest = {"seed_date": "2026-09-10", "finished_date": "2026-09-10", "server_arrivals": endpoints}
    return summary, manifest


class IndexExperimentTests(unittest.TestCase):
    def test_warmup_reads_each_index_once_and_quotes_names(self):
        rows = "todos\t0\tPRIMARY\t1\tid\ntodos\t1\tidx_user_status\t1\tuser_id\n" \
               "todos\t1\tidx_user_status\t2\tstatus\ntodos\t1\tquoted`name\t1\tid\n"
        sql = experiment.index_warmup_sql(rows)
        self.assertEqual(sql.count("SELECT COUNT(*)"), 3)
        self.assertEqual(sql.count("`idx_user_status`"), 1)
        self.assertIn("`quoted``name`", sql)

    def test_executed_audit_does_not_turn_dropped_load_into_capacity_success(self):
        summary, manifest = evidence()
        before = copy.deepcopy(summary)
        self.assertEqual(experiment.executed_prefix(FIXTURE, summary, manifest), (2, []))
        self.assertIsNone(audit.planned_unique_completions(FIXTURE, summary)[0])
        self.assertEqual(summary, before)

    def test_every_partial_cycle_has_exact_endpoint_distribution(self):
        for iterations in range(10, 15):
            summary, manifest = evidence(iterations)
            self.assertEqual(experiment.executed_prefix(FIXTURE, summary, manifest), (2, []))

    def test_missing_counter_does_not_mean_zero(self):
        summary, manifest = evidence()
        del summary["metrics"]["completion_success"]
        self.assertIsNone(experiment.executed_prefix(FIXTURE, summary, manifest)[0])

    def test_transport_or_business_failures_are_not_audited_as_full_prefix(self):
        for name in ("sent_requests", "business_success", "completion_success"):
            summary, manifest = evidence()
            summary["metrics"][name]["count"] -= 1
            self.assertIsNone(experiment.executed_prefix(FIXTURE, summary, manifest)[0])

    def test_server_mismatch_and_date_boundary_fail_closed(self):
        summary, manifest = evidence()
        manifest["server_arrivals"]["complete"] += 1
        self.assertIsNone(experiment.executed_prefix(FIXTURE, summary, manifest)[0])
        summary, manifest = evidence()
        manifest["finished_date"] = "2026-09-11"
        self.assertIsNone(experiment.executed_prefix(FIXTURE, summary, manifest)[0])

    def test_history_is_outside_active_ids_and_today(self):
        history = experiment.history_metadata(FIXTURE, 3)
        self.assertEqual((history["count"], history["start_id"], history["end_id"]),
                         (6, 910000020, 910000025))
        sql = experiment.history_sql(FIXTURE, history)
        self.assertEqual(sql.count("INSERT INTO todos"), 3)
        self.assertIn("'2026-08-08'", sql)
        self.assertIn("'2026-08-11 12:00:00'", sql)
        self.assertNotIn("'2026-09-10'", sql)
        self.assertIn("'COIN', 0, 0", sql)
        self.assertIn("CALL rougether_scale_seed_guard()", sql)

    def test_history_rejects_non_experiment_database_and_unbounded_size(self):
        with self.assertRaises(ValueError):
            experiment.history_metadata({**FIXTURE, "databaseName": "production"}, 3)
        for per_user in (-1, 1001):
            with self.assertRaises(ValueError):
                experiment.history_metadata(FIXTURE, per_user)
        with self.assertRaises(ValueError):
            experiment.history_metadata({**FIXTURE, "usersCount": 1000000}, 100)

    def test_history_snapshot_detects_changed_or_missing_rows(self):
        history = experiment.history_metadata(FIXTURE, 3)
        good = "database_ok\t1\nall_todos\t26\nhistorical_todos\t6\ninvalid_history\t0\nhistorical_reward_ledgers\t0\n"
        self.assertTrue(experiment.history_snapshot(lambda sql: good, FIXTURE, history)["passed"])
        for bad in (good.replace("invalid_history\t0", "invalid_history\t1"),
                    good.replace("historical_todos\t6", "historical_todos\t5")):
            self.assertFalse(experiment.history_snapshot(lambda sql: bad, FIXTURE, history)["passed"])


if __name__ == "__main__":
    unittest.main()
