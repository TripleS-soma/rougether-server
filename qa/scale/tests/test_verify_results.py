import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


VERIFY_PATH = Path(__file__).resolve().parents[1] / "verify-results.py"
AUDIT_PATH = Path(__file__).resolve().parents[1] / "seed" / "audit.py"
SPEC = importlib.util.spec_from_file_location("verify_results", VERIFY_PATH)
verify_results = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verify_results
SPEC.loader.exec_module(verify_results)
AUDIT_SPEC = importlib.util.spec_from_file_location("audit_runner", AUDIT_PATH)
audit_runner = importlib.util.module_from_spec(AUDIT_SPEC)
sys.modules[AUDIT_SPEC.name] = audit_runner
AUDIT_SPEC.loader.exec_module(audit_runner)


def metric(**values):
    return {"values": values}


def good_manifest():
    return {
        "scenario": "mixed",
        "rate": 100,
        "duration_seconds": 10,
        "expected_requests": 1000,
        "preallocated_vus": 50,
        "max_vus": 100,
        "started_at": "2026-09-09T10:00:00+09:00",
        "completed_at": "2026-09-09T10:00:10+09:00",
        "source_sha": "abc1234",
        "k6_exit_code": 0,
        "seed_date": "2026-09-09",
        "finished_date": "2026-09-09",
        "host_cpu_count": 8,
        "cleanup_completed": True,
        "environment": "single-host Docker Desktop; loopback generator",
        "limits": {"SCALE_API_MEMORY": "2g"},
        "docker": {"cpus": 8, "memory_bytes": 8589934592},
        "containers": {
            "api": {
                "image_id": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "memory_bytes": 2147483648,
                "nano_cpus": 2000000000,
                "image": {
                    "architecture": "arm64",
                    "repo_digests": [
                        "eclipse-temurin@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    ],
                },
            },
            "mysql": {
                "image_id": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "memory_bytes": 2147483648,
                "nano_cpus": 2000000000,
                "image": {
                    "architecture": "arm64",
                    "repo_digests": [
                        "mysql@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    ],
                },
            },
        },
        "server_arrivals": {"me": 400, "today": 400, "complete": 200},
    }


def good_summary():
    metrics = {
        "sent_requests": metric(count=1000),
        "business_success": metric(count=1000),
        "business_success_rate": metric(rate=1.0),
        "valid_outcomes": metric(count=1000),
        "valid_outcome_rate": metric(rate=1.0),
        "unexpected_failure": metric(rate=0.0),
        "expected_conflicts": metric(count=0),
        "rejected_requests": metric(count=0),
        "rejected_request_rate": metric(rate=0.0),
        "response_latency": metric(**{"p(95)": 90, "p(99)": 120}),
        "successful_latency": metric(**{"p(95)": 90, "p(99)": 120}),
        "completion_success": metric(count=200),
        "reward_amount": metric(count=2000),
        "dropped_iterations": metric(count=0),
        "iterations": metric(count=1000),
        "http_reqs": metric(count=1000),
        "generator_active_vus": metric(value=25),
    }
    for endpoint, count, p95, p99 in (
        ("me", 400, 80, 120),
        ("today", 400, 90, 130),
        ("complete", 200, 180, 240),
    ):
        metrics[f"sent_requests{{endpoint:{endpoint}}}"] = metric(count=count)
        metrics[f"business_success{{endpoint:{endpoint}}}"] = metric(count=count)
        metrics[f"business_success_rate{{endpoint:{endpoint}}}"] = metric(rate=1.0)
        metrics[f"valid_outcome_rate{{endpoint:{endpoint}}}"] = metric(rate=1.0)
        metrics[f"response_latency{{endpoint:{endpoint}}}"] = metric(**{"p(95)": p95, "p(99)": p99})
        metrics[f"successful_latency{{endpoint:{endpoint}}}"] = metric(**{"p(95)": p95, "p(99)": p99})
    return {
        "metrics": metrics,
        "profile": "mixed",
        "config": {"rate": 100, "durationSeconds": 10},
        "audit": {"requestShape": "four reads then one write by iterationInTest"},
    }


def good_audit():
    return {
        "passed": True,
        "date": "2026-09-09",
        "checked_tables": ["todos", "wallet_histories", "user_wallets", "personal_rooms"],
        "completion_audit": {
            "expected_unique_completions": 200,
            "actual_completed_todos": 200,
            "completed_outside_expected_prefix": 0,
            "missing_expected_prefix_completion": 0,
            "owner_mismatch": 0,
        },
        "reward_audit": {
            "expected_reward_total": 2000,
            "actual_todo_reward_total": 2000,
            "duplicate_history_sources": 0,
            "history_owner_swaps": 0,
            "rewarded_users": 50,
        },
        "violations": [],
    }


class VerifyResultsTest(unittest.TestCase):
    def verify(self, mutate=None):
        manifest = good_manifest()
        summary = good_summary()
        audit = good_audit()
        telemetry = [
            {
                "at": "2026-09-09T01:00:00+00:00",
                "generator": {"cpu_percent": 50.0, "rss_kib": 100000, "elapsed": "00:01"},
                "containers": [{"Name": "api", "CPUPerc": "50%"}],
                "jvm": {"jvm.memory.used": {"measurements": []}},
            }
        ]
        if mutate:
            mutate(manifest, summary, audit, telemetry)
        with tempfile.TemporaryDirectory() as raw_tmp:
            run_dir = Path(raw_tmp)
            (run_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (run_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
            (run_dir / "db-audit.json").write_text(json.dumps(audit), encoding="utf-8")
            (run_dir / "telemetry.jsonl").write_text(
                "\n".join(json.dumps(sample) for sample in telemetry) + "\n",
                encoding="utf-8",
            )
            verifier = verify_results.Verifier(run_dir)
            return verifier.verify()

    def assertFailsWith(self, verdict, text):
        self.assertFalse(verdict["passed"], verdict)
        self.assertIn(text, "\n".join(verdict["failures"] + verdict["measurement_limits"]))

    def test_good_run_passes(self):
        verdict = self.verify()
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual([], verdict["failures"])
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_index_experiment_requires_separate_history_and_executed_audits(self):
        verdict = self.verify(lambda manifest, *_args: manifest.update({"history": {"count": 100}}))
        self.assertFailsWith(verdict, "missing required artifact: history-before.json")
        self.assertFailsWith(verdict, "missing required artifact: executed-db-audit.json")

    def test_index_audit_rejects_false_pass_when_history_values_differ(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            run_dir = Path(raw_tmp)
            for name in ("history-before.json", "history-after.json"):
                (run_dir / name).write_text(json.dumps(
                    {"passed": True, "actual": {"count": 99}, "expected": {"count": 100}}))
            (run_dir / "executed-db-audit.json").write_text(json.dumps(
                {"passed": True, "scope": "executed_requests_not_offered_load",
                 "errors": [], "history_unchanged": True}))
            verifier = verify_results.Verifier(run_dir)
            verifier._verify_index_experiment()
            self.assertIn("index history snapshot mismatch: history-after.json", verifier.failures)

    def test_cli_writes_verdict_and_returns_nonzero_on_failure(self):
        with tempfile.TemporaryDirectory() as raw_tmp:
            run_dir = Path(raw_tmp)
            manifest = good_manifest()
            summary = good_summary()
            audit = good_audit()
            del summary["metrics"]["sent_requests"]
            (run_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (run_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
            (run_dir / "db-audit.json").write_text(json.dumps(audit), encoding="utf-8")

            completed = subprocess.run(
                [sys.executable, str(VERIFY_PATH), "--run-dir", str(run_dir)],
                check=False,
                text=True,
                capture_output=True,
            )

            self.assertNotEqual(0, completed.returncode)
            written = json.loads((run_dir / "verdict.json").read_text(encoding="utf-8"))
            self.assertFalse(written["passed"])
            self.assertIn("sent_requests", "\n".join(written["failures"]))

    def test_missing_manifest_field_fails(self):
        verdict = self.verify(lambda manifest, _summary, _audit, _telemetry: manifest.pop("source_sha"))
        self.assertFailsWith(verdict, "manifest missing fields")

    def test_malformed_summary_fails(self):
        verdict = self.verify(lambda _manifest, summary, _audit, _telemetry: summary.update({"metrics": []}))
        self.assertFailsWith(verdict, "summary.metrics must be an object")

    def test_corrupted_db_audit_fails_even_when_http_successful(self):
        verdict = self.verify(
            lambda _manifest, _summary, audit, _telemetry: audit.update(
                {"passed": False, "violations": ["user 900000000 COIN wallet expected 50, actual rows=1 sum=40"]}
            )
        )
        self.assertFailsWith(verdict, "db-audit has violations")

    def test_generator_under_delivery_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"]["sent_requests"]["values"].update(
                {"count": 980}
            )
        )
        self.assertFailsWith(verdict, "generator under-delivered")

    def test_dropped_iterations_fail(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"]["dropped_iterations"]["values"].update(
                {"count": 1}
            )
        )
        self.assertFailsWith(verdict, "dropped_iterations must be zero")

    def test_expected_409_counted_as_business_success_fails(self):
        def mutate(_manifest, summary, _audit, _telemetry):
            summary["metrics"]["sent_requests"]["values"]["count"] = 1000
            summary["metrics"]["business_success"]["values"]["count"] = 1000
            summary["metrics"]["expected_conflicts"]["values"]["count"] = 3

        verdict = self.verify(mutate)
        self.assertFailsWith(verdict, "expected_conflicts must be zero outside contention")

    def test_absent_endpoint_stats_fail(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"].pop(
                "business_success_rate{endpoint:today}"
            )
        )
        self.assertFailsWith(verdict, "missing endpoint business_success_rate for today")

    def test_bad_endpoint_latency_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"][
                "response_latency{endpoint:complete}"
            ]["values"].update({"p(95)": 501})
        )
        self.assertFailsWith(verdict, "endpoint complete latency p(95)")

    def test_response_latency_fails_even_when_successful_latency_is_fast(self):
        def mutate(manifest, summary, _audit, _telemetry):
            manifest["scenario"] = "contention"
            manifest["server_arrivals"] = {"me": 0, "today": 0, "complete": 1000}
            summary["metrics"]["expected_conflicts"]["values"]["count"] = 10
            summary["metrics"]["business_success"]["values"]["count"] = 990
            summary["metrics"]["business_success{endpoint:complete}"]["values"]["count"] = 990
            summary["metrics"]["sent_requests{endpoint:complete}"]["values"]["count"] = 1000
            summary["metrics"]["valid_outcome_rate"]["values"]["rate"] = 1.0
            summary["metrics"]["valid_outcome_rate{endpoint:complete}"]["values"]["rate"] = 1.0
            summary["metrics"]["successful_latency{endpoint:complete}"]["values"].update(
                {"p(95)": 5, "p(99)": 5}
            )
            summary["metrics"]["response_latency{endpoint:complete}"]["values"].update(
                {"p(95)": 1000, "p(99)": 1000}
            )

        verdict = self.verify(mutate)
        self.assertFailsWith(verdict, "endpoint complete latency p(95)")

    def test_nan_metric_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"]["business_success_rate"][
                "values"
            ].update({"rate": float("nan")})
        )
        self.assertFailsWith(verdict, "business_success_rate.values.rate must be finite")

    def test_zero_success_counter_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"]["completion_success"][
                "values"
            ].update({"count": 0})
        )
        self.assertFailsWith(verdict, "completion_success.values.count must be greater than zero")

    def test_nonzero_k6_exit_fails_capacity(self):
        verdict = self.verify(lambda manifest, _summary, _audit, _telemetry: manifest.update({"k6_exit_code": 99}))
        self.assertFalse(verdict["passed"])
        self.assertEqual("capacity_failed", verdict["classification"])

    def test_kst_midnight_crossing_is_reported_as_measurement_limit(self):
        verdict = self.verify(lambda manifest, _summary, _audit, _telemetry: manifest.update({"finished_date": "2026-09-10"}))
        self.assertFalse(verdict["passed"], verdict)
        self.assertIn("KST midnight crossed", "\n".join(verdict["measurement_limits"]))

    def test_read_scenario_only_requires_read_endpoints(self):
        verdict = self.verify(
            lambda manifest, summary, _audit, _telemetry: (
                manifest.update({"scenario": "read"}),
                manifest.update({"expected_requests": 1000}),
                manifest.update({"server_arrivals": {"me": 500, "today": 500, "complete": 0}}),
                summary["metrics"]["sent_requests{endpoint:me}"]["values"].update({"count": 500}),
                summary["metrics"]["sent_requests{endpoint:today}"]["values"].update({"count": 500}),
                summary["metrics"]["business_success{endpoint:me}"]["values"].update({"count": 500}),
                summary["metrics"]["business_success{endpoint:today}"]["values"].update({"count": 500}),
                summary["metrics"]["completion_success"]["values"].update({"count": 0}),
                summary["metrics"]["reward_amount"]["values"].update({"count": 0}),
                summary["metrics"].pop("business_success_rate{endpoint:complete}"),
                summary["metrics"].pop("sent_requests{endpoint:complete}"),
                summary["metrics"].pop("business_success{endpoint:complete}"),
                summary["metrics"].pop("successful_latency{endpoint:complete}"),
            )
        )
        self.assertTrue(verdict["passed"], verdict)

    def test_write_scenario_only_requires_complete_endpoint(self):
        verdict = self.verify(
            lambda manifest, summary, _audit, _telemetry: (
                manifest.update({"scenario": "write"}),
                manifest.update({"server_arrivals": {"me": 0, "today": 0, "complete": 1000}}),
                summary["metrics"]["sent_requests{endpoint:complete}"]["values"].update({"count": 1000}),
                summary["metrics"]["business_success{endpoint:complete}"]["values"].update({"count": 1000}),
                summary["metrics"].pop("business_success_rate{endpoint:me}"),
                summary["metrics"].pop("business_success_rate{endpoint:today}"),
                summary["metrics"].pop("sent_requests{endpoint:me}"),
                summary["metrics"].pop("sent_requests{endpoint:today}"),
                summary["metrics"].pop("business_success{endpoint:me}"),
                summary["metrics"].pop("business_success{endpoint:today}"),
                summary["metrics"].pop("successful_latency{endpoint:me}"),
                summary["metrics"].pop("successful_latency{endpoint:today}"),
            )
        )
        self.assertTrue(verdict["passed"], verdict)

    def test_mixed_latency_uses_300_1000_for_all_endpoints(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"][
                "successful_latency{endpoint:me}"
            ]["values"].update({"p(95)": 250, "p(99)": 700})
        )
        self.assertTrue(verdict["passed"], verdict)

    def test_contention_is_diagnostic_and_allows_expected_conflicts(self):
        def mutate(manifest, summary, _audit, _telemetry):
            manifest["scenario"] = "contention"
            manifest["server_arrivals"] = {"me": 0, "today": 0, "complete": 1000}
            summary["metrics"]["expected_conflicts"]["values"]["count"] = 10
            summary["metrics"]["business_success"]["values"]["count"] = 990
            summary["metrics"]["business_success{endpoint:complete}"]["values"]["count"] = 990
            summary["metrics"]["business_success_rate"]["values"]["rate"] = 0.99
            summary["metrics"]["business_success_rate{endpoint:complete}"]["values"]["rate"] = 0.99
            summary["metrics"]["sent_requests{endpoint:complete}"]["values"]["count"] = 1000

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("diagnostic", verdict["classification"])

    def test_contention_409_counted_as_success_fails(self):
        def mutate(manifest, summary, _audit, _telemetry):
            manifest["scenario"] = "contention"
            manifest["server_arrivals"] = {"me": 0, "today": 0, "complete": 1000}
            summary["metrics"]["expected_conflicts"]["values"]["count"] = 10
            summary["metrics"]["business_success"]["values"]["count"] = 1000
            summary["metrics"]["business_success{endpoint:complete}"]["values"]["count"] = 1000
            summary["metrics"]["business_success_rate"]["values"]["rate"] = 1.0
            summary["metrics"]["sent_requests{endpoint:complete}"]["values"]["count"] = 1000

        verdict = self.verify(mutate)
        self.assertFailsWith(verdict, "business_success appears to include expected_conflicts")

    def test_execution_error_fails(self):
        verdict = self.verify(
            lambda manifest, _summary, _audit, _telemetry: manifest.update(
                {"execution_error": "fixture failed"}
            )
        )
        self.assertFailsWith(verdict, "manifest.execution_error present")

    def test_cleanup_false_fails(self):
        verdict = self.verify(
            lambda manifest, _summary, _audit, _telemetry: manifest.update(
                {"cleanup_completed": False}
            )
        )
        self.assertFailsWith(verdict, "manifest.cleanup_completed must be true")

    def test_missing_generator_telemetry_fails_capacity_unverified(self):
        verdict = self.verify(lambda _manifest, _summary, _audit, telemetry: telemetry.clear())
        self.assertFalse(verdict["passed"])
        self.assertEqual("capacity_unverified", verdict["classification"])
        self.assertIn("capacity is unverified", "\n".join(verdict["measurement_limits"]))

    def test_generator_saturation_does_not_claim_capacity(self):
        verdict = self.verify(
            lambda _manifest, _summary, _audit, telemetry: telemetry[0]["generator"].update(
                {"cpu_percent": 99.0}
            )
        )
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_three_generator_samples_can_claim_capacity(self):
        def mutate(_manifest, _summary, _audit, telemetry):
            telemetry.extend([dict(telemetry[0]), dict(telemetry[0])])

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity", verdict["classification"])

    def test_missing_container_provenance_with_three_samples_is_unverified(self):
        def mutate(manifest, _summary, _audit, telemetry):
            manifest.pop("containers")
            telemetry.extend([dict(telemetry[0]), dict(telemetry[0])])

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_incomplete_container_digest_with_three_samples_is_unverified(self):
        def mutate(manifest, _summary, _audit, telemetry):
            manifest["containers"]["api"]["image"]["repo_digests"] = []
            telemetry.extend([dict(telemetry[0]), dict(telemetry[0])])

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_missing_server_arrivals_with_three_samples_is_unverified(self):
        def mutate(manifest, _summary, _audit, telemetry):
            manifest.pop("server_arrivals")
            telemetry.extend([dict(telemetry[0]), dict(telemetry[0])])

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_http_reqs_mismatch_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"]["http_reqs"][
                "values"
            ].update({"count": 999})
        )
        self.assertFailsWith(verdict, "sent_requests must equal http_reqs")

    def test_iterations_mismatch_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"]["iterations"][
                "values"
            ].update({"count": 999})
        )
        self.assertFailsWith(verdict, "each iteration must map to one HTTP request")

    def test_sent_plus_dropped_must_match_offered_load(self):
        def mutate(_manifest, summary, _audit, _telemetry):
            summary["metrics"]["sent_requests"]["values"]["count"] = 1001
            summary["metrics"]["http_reqs"]["values"]["count"] = 1001
            summary["metrics"]["iterations"]["values"]["count"] = 1001
            summary["metrics"]["dropped_iterations"]["values"]["count"] = 1

        verdict = self.verify(mutate)
        self.assertFailsWith(verdict, "sent_requests + dropped_iterations must match expected_requests")

    def test_sent_plus_dropped_allows_one_request_k6_boundary(self):
        def mutate(manifest, summary, _audit, telemetry):
            manifest["expected_requests"] = 3000
            manifest["rate"] = 100
            manifest["duration_seconds"] = 30
            manifest["server_arrivals"] = {"me": 1200, "today": 1200, "complete": 601}
            summary["metrics"]["sent_requests"]["values"]["count"] = 3001
            summary["metrics"]["http_reqs"]["values"]["count"] = 3001
            summary["metrics"]["iterations"]["values"]["count"] = 3001
            summary["metrics"]["business_success"]["values"]["count"] = 3001
            summary["metrics"]["valid_outcomes"]["values"]["count"] = 3001
            summary["metrics"]["sent_requests{endpoint:me}"]["values"]["count"] = 1200
            summary["metrics"]["sent_requests{endpoint:today}"]["values"]["count"] = 1200
            summary["metrics"]["sent_requests{endpoint:complete}"]["values"]["count"] = 601
            summary["metrics"]["business_success{endpoint:me}"]["values"]["count"] = 1200
            summary["metrics"]["business_success{endpoint:today}"]["values"]["count"] = 1200
            summary["metrics"]["business_success{endpoint:complete}"]["values"]["count"] = 601
            summary["metrics"]["completion_success"]["values"]["count"] = 601
            telemetry.extend([dict(telemetry[0]), dict(telemetry[0])])

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)

    def test_endpoint_sent_total_mismatch_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"][
                "sent_requests{endpoint:complete}"
            ]["values"].update({"count": 199})
        )
        self.assertFailsWith(verdict, "endpoint sent_requests counts must sum")

    def test_endpoint_business_success_total_mismatch_fails_even_when_rates_pass(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"][
                "business_success{endpoint:complete}"
            ]["values"].update({"count": 199})
        )
        self.assertFailsWith(verdict, "endpoint business_success counts must sum")

    def test_missing_dropped_iterations_metric_fails(self):
        verdict = self.verify(
            lambda _manifest, summary, _audit, _telemetry: summary["metrics"].pop("dropped_iterations")
        )
        self.assertFailsWith(verdict, "summary missing metrics")

    def test_flat_metric_values_from_current_runner_pass(self):
        def mutate(_manifest, summary, _audit, telemetry):
            telemetry.extend([dict(telemetry[0]), dict(telemetry[0])])
            for name, value in list(summary["metrics"].items()):
                if isinstance(value, dict) and "values" in value:
                    summary["metrics"][name] = value["values"]

        verdict = self.verify(mutate)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity", verdict["classification"])

    def test_optional_detailed_audit_detects_duplicate_rewards(self):
        def mutate(_manifest, _summary, audit, _telemetry):
            audit["reward_audit"]["duplicate_history_sources"] = 1

        verdict = self.verify(mutate)
        self.assertFailsWith(verdict, "db-audit found duplicate wallet history sources")

    def test_failed_audit_with_null_expected_does_not_fabricate_db_corruption(self):
        def mutate(_manifest, _summary, audit, _telemetry):
            audit["passed"] = False
            audit["violations"] = ["summary missing iterations"]
            audit["completion_audit"]["expected_unique_completions"] = None
            audit["completion_audit"]["actual_completed_todos"] = 5592
            audit["completion_audit"]["completed_outside_expected_prefix"] = None
            audit["completion_audit"]["missing_expected_prefix_completion"] = None
            audit["reward_audit"]["expected_reward_total"] = None
            audit["reward_audit"]["actual_todo_reward_total"] = 55920
            audit["reward_audit"]["rewarded_users"] = None

        verdict = self.verify(mutate)
        self.assertFalse(verdict["passed"])
        text = "\n".join(verdict["failures"])
        self.assertIn("db-audit has violations", text)
        self.assertNotIn("completed todo count differs", text)
        self.assertNotIn("reward total differs", text)

    def test_server_arrivals_endpoint_mismatch_fails(self):
        verdict = self.verify(
            lambda manifest, _summary, _audit, _telemetry: manifest["server_arrivals"].update(
                {"complete": 199}
            )
        )
        self.assertFailsWith(verdict, "manifest.server_arrivals.complete must match endpoint sent_requests")

    def test_server_arrivals_non_profile_endpoint_must_be_zero(self):
        verdict = self.verify(
            lambda manifest, summary, _audit, _telemetry: (
                manifest.update({"scenario": "read"}),
                manifest.update({"server_arrivals": {"me": 500, "today": 500, "complete": 1}}),
                summary["metrics"]["sent_requests{endpoint:me}"]["values"].update({"count": 500}),
                summary["metrics"]["sent_requests{endpoint:today}"]["values"].update({"count": 500}),
                summary["metrics"]["business_success{endpoint:me}"]["values"].update({"count": 500}),
                summary["metrics"]["business_success{endpoint:today}"]["values"].update({"count": 500}),
                summary["metrics"]["completion_success"]["values"].update({"count": 0}),
                summary["metrics"]["reward_amount"]["values"].update({"count": 0}),
            )
        )
        self.assertFailsWith(verdict, "manifest.server_arrivals.complete must be zero for read")

    def test_missing_container_stats_downgrades_capacity(self):
        verdict = self.verify(
            lambda _manifest, _summary, _audit, telemetry: telemetry[0].pop("containers")
        )
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_missing_jvm_stats_downgrades_capacity(self):
        verdict = self.verify(lambda _manifest, _summary, _audit, telemetry: telemetry[0].pop("jvm"))
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity_unverified", verdict["classification"])

    def test_real_shaped_summary_audit_report_then_verifier_passes(self):
        manifest = good_manifest()
        manifest.update({"scenario": "read", "rate": 20, "duration_seconds": 15, "expected_requests": 300})
        manifest["server_arrivals"] = {"me": 150, "today": 150, "complete": 0}
        summary = good_summary()
        summary.update({
            "profile": "read",
            "config": {"rate": 20, "durationSeconds": 15},
            "audit": {"requestShape": "one read request per iteration"},
        })
        summary["metrics"]["sent_requests"]["values"]["count"] = 300
        summary["metrics"]["business_success"]["values"]["count"] = 300
        summary["metrics"]["valid_outcomes"]["values"]["count"] = 300
        summary["metrics"]["http_reqs"]["values"]["count"] = 300
        summary["metrics"]["iterations"]["values"]["count"] = 300
        summary["metrics"]["completion_success"]["values"]["count"] = 0
        summary["metrics"]["reward_amount"]["values"]["count"] = 0
        summary["metrics"]["sent_requests{endpoint:me}"]["values"]["count"] = 150
        summary["metrics"]["sent_requests{endpoint:today}"]["values"]["count"] = 150
        summary["metrics"]["business_success{endpoint:me}"]["values"]["count"] = 150
        summary["metrics"]["business_success{endpoint:today}"]["values"]["count"] = 150
        for name, value in list(summary["metrics"].items()):
            if name.endswith("{endpoint:complete}"):
                summary["metrics"].pop(name)
            elif isinstance(value, dict) and "values" in value:
                summary["metrics"][name] = value["values"]

        fixture = {
            "databaseName": "rougether_scale",
            "date": "2026-09-09",
            "userStartId": 900000000,
            "usersCount": 2,
            "todoStartId": 910000000,
            "todoCount": 20,
            "reward": {"todoCompleteAmount": 10, "dailyCap": 50},
        }
        snapshot = {
            "scalars": {
                "meta_database_ok": 1,
                "todo_total": 20,
                "owner_mismatch": 0,
                "duplicate_history": 0,
                "history_swaps": 0,
                "completed_outside_expected_prefix": 0,
                "missing_expected_prefix_completion": 0,
            },
            "completed": {},
            "history": {},
            "wallet": {900000000: (1, 0), 900000001: (1, 0)},
            "room": {900000000: (1, 0), 900000001: (1, 0)},
        }
        expected_unique, plan_violations = audit_runner.planned_unique_completions(fixture, summary)
        expected = audit_runner.expected_from_prefix(fixture, expected_unique or 0)
        violations = list(plan_violations)
        violations.extend(audit_runner.audit_snapshot(fixture, expected, snapshot))
        completion_audit, reward_audit = audit_runner.audit_sections(
            fixture,
            expected_unique,
            expected,
            snapshot,
        )
        db_audit = {
            "passed": not violations,
            "date": fixture["date"],
            "checked_tables": ["todos", "wallet_histories", "user_wallets", "personal_rooms"],
            "completion_audit": completion_audit,
            "reward_audit": reward_audit,
            "violations": violations,
        }
        telemetry = [
            {
                "at": "2026-09-09T01:00:00+00:00",
                "generator": {"cpu_percent": 2.0, "rss_kib": 100000, "elapsed": "00:01"},
                "containers": [{"Name": "api"}],
                "jvm": {"jvm.memory.used": {"measurements": []}},
            },
            {
                "at": "2026-09-09T01:00:05+00:00",
                "generator": {"cpu_percent": 2.0, "rss_kib": 100000, "elapsed": "00:06"},
                "containers": [{"Name": "api"}],
                "jvm": {"jvm.memory.used": {"measurements": []}},
            },
            {
                "at": "2026-09-09T01:00:10+00:00",
                "generator": {"cpu_percent": 2.0, "rss_kib": 100000, "elapsed": "00:11"},
                "containers": [{"Name": "api"}],
                "jvm": {"jvm.memory.used": {"measurements": []}},
            },
        ]
        with tempfile.TemporaryDirectory() as raw_tmp:
            run_dir = Path(raw_tmp)
            (run_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (run_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
            (run_dir / "db-audit.json").write_text(json.dumps(db_audit), encoding="utf-8")
            (run_dir / "telemetry.jsonl").write_text(
                "\n".join(json.dumps(sample) for sample in telemetry) + "\n",
                encoding="utf-8",
            )
            verdict = verify_results.Verifier(run_dir).verify()

        self.assertTrue(db_audit["passed"], db_audit)
        self.assertTrue(verdict["passed"], verdict)
        self.assertEqual("capacity", verdict["classification"])


if __name__ == "__main__":
    unittest.main()
