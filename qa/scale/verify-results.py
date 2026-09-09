#!/usr/bin/env python3
"""Fail-closed verifier for Rougether scale test result directories."""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


REQUIRED_MANIFEST_FIELDS = {
    "scenario",
    "rate",
    "duration_seconds",
    "expected_requests",
    "preallocated_vus",
    "max_vus",
    "started_at",
    "completed_at",
    "source_sha",
    "k6_exit_code",
    "seed_date",
    "finished_date",
    "host_cpu_count",
    "cleanup_completed",
}

REQUIRED_METRICS = {
    "sent_requests",
    "business_success",
    "business_success_rate",
    "valid_outcomes",
    "valid_outcome_rate",
    "unexpected_failure",
    "expected_conflicts",
    "rejected_requests",
    "rejected_request_rate",
    "response_latency",
    "successful_latency",
    "completion_success",
    "reward_amount",
    "dropped_iterations",
    "iterations",
    "http_reqs",
}

REQUIRED_ENDPOINTS = {
    "read": {"me", "today"},
    "mixed": {"me", "today", "complete"},
    "write": {"complete"},
    "contention": {"complete"},
}

COMPLETION_SCENARIOS = {"mixed", "write", "contention"}
MIN_GENERATOR_SAMPLES_FOR_CAPACITY = 3

LATENCY_LIMITS_MS = {
    "read": {"p(95)": 200.0, "p(99)": 500.0},
    "mixed": {"p(95)": 300.0, "p(99)": 1000.0},
    "write": {"p(95)": 500.0, "p(99)": 1000.0},
    "contention": {"p(95)": 500.0, "p(99)": 1000.0},
}

MIN_SENT_RATIO = 0.99
MIN_BUSINESS_SUCCESS_RATE = 0.999
MAX_UNEXPECTED_FAILURE_RATE = 0.001
REQUIRED_CONTAINER_NAMES = ("api", "mysql")
REQUIRED_CONTAINER_FIELDS = ("image_id", "memory_bytes", "nano_cpus")


@dataclass
class Verifier:
    run_dir: Path
    failures: list[str] = field(default_factory=list)
    measurement_limits: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    details: dict[str, Any] = field(default_factory=dict)
    telemetry_unverified: bool = False

    def verify(self) -> dict[str, Any]:
        manifest = self._read_json("manifest.json")
        summary = self._read_json("summary.json")
        audit = self._read_json("db-audit.json")

        if manifest is not None:
            self._verify_manifest(manifest)
        if summary is not None and manifest is not None:
            self._verify_summary(summary, manifest)
        if audit is not None:
            self._verify_db_audit(audit)
        if manifest is not None:
            self._verify_telemetry()

        passed = not self.failures
        scenario = manifest.get("scenario") if isinstance(manifest, dict) else None
        verdict = {
            "passed": passed,
            "classification": self._classification(passed, scenario),
            "failures": self.failures,
            "measurement_limits": self.measurement_limits,
            "warnings": self.warnings,
            "details": self.details,
        }
        if self.failures and scenario != "contention" and self._manifest_exit_code_nonzero(manifest):
            verdict["classification"] = "capacity_failed"
        return verdict

    def _read_json(self, name: str) -> dict[str, Any] | None:
        path = self.run_dir / name
        if not path.is_file():
            self.failures.append(f"missing required artifact: {name}")
            return None
        try:
            with path.open("r", encoding="utf-8") as handle:
                value = json.load(handle)
        except (OSError, json.JSONDecodeError) as exc:
            self.failures.append(f"invalid JSON in {name}: {exc}")
            return None
        if not isinstance(value, dict):
            self.failures.append(f"{name} must be a JSON object")
            return None
        return value

    def _verify_manifest(self, manifest: dict[str, Any]) -> None:
        missing = sorted(REQUIRED_MANIFEST_FIELDS - manifest.keys())
        if missing:
            self.failures.append(f"manifest missing fields: {', '.join(missing)}")

        rate = self._finite_number(
            manifest.get("rate"),
            "manifest.rate",
            positive=True,
            allow_zero=False,
        )
        duration = self._finite_number(
            manifest.get("duration_seconds"),
            "manifest.duration_seconds",
            positive=True,
            allow_zero=False,
        )
        expected = self._finite_number(
            manifest.get("expected_requests"),
            "manifest.expected_requests",
            positive=True,
            allow_zero=False,
        )
        if rate is not None and duration is not None and expected is not None:
            offered = rate * duration
            self.details["offered_requests"] = offered
            if not math.isclose(expected, offered, rel_tol=0.001, abs_tol=1.0):
                self.failures.append(
                    "manifest.expected_requests must match rate * duration_seconds "
                    f"({expected} != {offered})"
                )

        for field_name in ("preallocated_vus", "max_vus"):
            self._finite_number(
                manifest.get(field_name),
                f"manifest.{field_name}",
                positive=True,
                allow_zero=False,
            )
        self._finite_number(
            manifest.get("host_cpu_count"),
            "manifest.host_cpu_count",
            positive=True,
            allow_zero=False,
        )

        k6_exit_code = manifest.get("k6_exit_code")
        if not isinstance(k6_exit_code, int) or isinstance(k6_exit_code, bool):
            self.failures.append("manifest.k6_exit_code must be an integer")
        elif k6_exit_code != 0:
            self.failures.append(f"k6 exited non-zero: {k6_exit_code}")

        if manifest.get("cleanup_completed") is not True:
            self.failures.append("manifest.cleanup_completed must be true")
        execution_error = manifest.get("execution_error")
        if execution_error:
            self.failures.append(f"manifest.execution_error present: {execution_error}")
        audit_exit_code = manifest.get("audit_exit_code")
        if audit_exit_code not in (None, 0):
            self.failures.append(f"manifest.audit_exit_code must be zero when present, got {audit_exit_code}")
        for field_name in ("environment", "limits", "docker"):
            if field_name not in manifest:
                self.measurement_limits.append(f"manifest.{field_name} missing; environment evidence is incomplete")
                self.telemetry_unverified = True
        self._verify_container_evidence(manifest)
        if "server_arrivals" not in manifest:
            self.measurement_limits.append("manifest.server_arrivals missing; server-side arrival evidence is unavailable")
            self.telemetry_unverified = True

        seed_date = manifest.get("seed_date")
        finished_date = manifest.get("finished_date")
        if not isinstance(seed_date, str) or not seed_date:
            self.failures.append("manifest.seed_date must be a non-empty string")
        if not isinstance(finished_date, str) or not finished_date:
            self.failures.append("manifest.finished_date must be a non-empty string")
        if isinstance(seed_date, str) and isinstance(finished_date, str) and seed_date != finished_date:
            self.failures.append(
                f"finished_date differs from seed_date ({seed_date} -> {finished_date}); KST midnight crossed"
            )
            self.measurement_limits.append(
                f"finished_date differs from seed_date ({seed_date} -> {finished_date}); KST midnight crossed"
            )

        for field_name in ("scenario", "started_at", "completed_at", "source_sha"):
            value = manifest.get(field_name)
            if not isinstance(value, str) or not value.strip():
                self.failures.append(f"manifest.{field_name} must be a non-empty string")
        scenario = manifest.get("scenario")
        if isinstance(scenario, str) and scenario not in REQUIRED_ENDPOINTS:
            self.failures.append(f"unsupported scenario: {scenario}")

    def _verify_summary(self, summary: dict[str, Any], manifest: dict[str, Any]) -> None:
        metrics = summary.get("metrics")
        if not isinstance(metrics, dict):
            self.failures.append("summary.metrics must be an object")
            return

        missing = sorted(name for name in REQUIRED_METRICS if name not in metrics)
        if missing:
            self.failures.append(f"summary missing metrics: {', '.join(missing)}")

        expected_requests = self._finite_number(
            manifest.get("expected_requests"),
            "manifest.expected_requests",
            positive=True,
            allow_zero=False,
        )
        sent = self._metric_number(metrics, "sent_requests", ["count"])
        http_reqs = self._metric_number(metrics, "http_reqs", ["count"])
        iterations = self._metric_number(metrics, "iterations", ["count"])
        dropped = self._metric_number(metrics, "dropped_iterations", ["count"], allow_zero=True)
        business_success = self._metric_number(metrics, "business_success", ["count"])
        business_success_rate = self._metric_number(metrics, "business_success_rate", ["rate", "value"])
        valid_outcomes = self._metric_number(metrics, "valid_outcomes", ["count"])
        valid_outcome_rate = self._metric_number(metrics, "valid_outcome_rate", ["rate", "value"])
        unexpected_failure = self._metric_number(
            metrics,
            "unexpected_failure",
            ["rate", "value"],
            allow_zero=True,
        )
        expected_conflicts = self._metric_number(metrics, "expected_conflicts", ["count"], allow_zero=True)
        rejected = self._metric_number(metrics, "rejected_requests", ["count"], allow_zero=True)

        scenario = manifest.get("scenario")
        has_completion_endpoint = scenario in COMPLETION_SCENARIOS
        self._metric_number(
            metrics,
            "completion_success",
            ["count"],
            allow_zero=not has_completion_endpoint,
        )
        self._metric_number(
            metrics,
            "reward_amount",
            ["count", "value", "rate"],
            allow_zero=not has_completion_endpoint,
        )

        if expected_requests is not None and sent is not None:
            minimum_sent = expected_requests * MIN_SENT_RATIO
            if sent < minimum_sent:
                self.failures.append(
                    f"generator under-delivered sent_requests: {sent} < {minimum_sent:.2f}"
                )
                self.measurement_limits.append("generator did not deliver at least 99% of offered load")
            self.details["sent_ratio"] = sent / expected_requests
        if expected_requests is not None and sent is not None and dropped is not None:
            delivered_or_dropped = sent + dropped
            if abs(delivered_or_dropped - expected_requests) > 1:
                self.failures.append(
                    "sent_requests + dropped_iterations must match expected_requests "
                    f"within 1 ({delivered_or_dropped} != {expected_requests})"
                )

        if sent is not None and http_reqs is not None and sent != http_reqs:
            self.failures.append(f"sent_requests must equal http_reqs ({sent} != {http_reqs})")
        if sent is not None and iterations is not None and sent != iterations:
            self.failures.append(f"each iteration must map to one HTTP request ({iterations} != {sent})")

        if dropped is not None and dropped != 0:
            self.failures.append(f"dropped_iterations must be zero, got {dropped}")
            self.measurement_limits.append("k6 dropped iterations; requested load was saturated")

        if (
            scenario != "contention"
            and business_success_rate is not None
            and business_success_rate < MIN_BUSINESS_SUCCESS_RATE
        ):
            self.failures.append(
                f"business_success_rate below {MIN_BUSINESS_SUCCESS_RATE}: {business_success_rate}"
            )
        if valid_outcome_rate is not None and valid_outcome_rate < MIN_BUSINESS_SUCCESS_RATE:
            self.failures.append(
                f"valid_outcome_rate below {MIN_BUSINESS_SUCCESS_RATE}: {valid_outcome_rate}"
            )
        if sent is not None and valid_outcomes is not None and valid_outcomes != sent:
            self.failures.append(f"valid_outcomes must equal sent_requests ({valid_outcomes} != {sent})")
        if unexpected_failure is not None and unexpected_failure > MAX_UNEXPECTED_FAILURE_RATE:
            self.failures.append(
                f"unexpected_failure above {MAX_UNEXPECTED_FAILURE_RATE}: {unexpected_failure}"
            )
        if rejected is not None and rejected != 0:
            self.measurement_limits.append(f"rejected_requests observed: {rejected}")

        if scenario != "contention" and expected_conflicts not in (None, 0):
            self.failures.append(f"expected_conflicts must be zero outside contention, got {expected_conflicts}")

        if scenario != "contention" and sent is not None and business_success is not None:
            if business_success + expected_conflicts > sent:
                self.failures.append(
                    "business_success appears to include expected_conflicts; "
                    f"{business_success} + {expected_conflicts} > {sent}"
                )
            if business_success > sent:
                self.failures.append(f"business_success exceeds sent_requests ({business_success} > {sent})")
        if (
            scenario == "contention"
            and sent is not None
            and business_success is not None
            and expected_conflicts is not None
        ):
            covered = business_success + expected_conflicts
            if covered > sent:
                self.failures.append(
                    "business_success appears to include expected_conflicts; "
                    f"{business_success} + {expected_conflicts} > {sent}"
                )
            elif covered != sent:
                self.failures.append(
                    f"contention business outcomes must cover sent_requests ({covered} != {sent})"
                )

        endpoint_counts = []
        endpoint_success_counts = []
        required_endpoints = sorted(REQUIRED_ENDPOINTS.get(str(scenario), set()))
        for endpoint in required_endpoints:
            endpoint_sent, endpoint_success = self._verify_endpoint(metrics, str(scenario), endpoint)
            if endpoint_sent is not None:
                endpoint_counts.append(endpoint_sent)
            if endpoint_success is not None:
                endpoint_success_counts.append(endpoint_success)

        required_endpoint_count = len(required_endpoints)
        if sent is not None and len(endpoint_counts) == required_endpoint_count:
            endpoint_sent_total = sum(endpoint_counts)
            if endpoint_sent_total != sent:
                self.failures.append(
                    f"endpoint sent_requests counts must sum to global sent_requests ({endpoint_sent_total} != {sent})"
                )
            self._verify_server_arrivals(manifest, str(scenario), endpoint_sent_total, {
                endpoint: count
                for endpoint, count in zip(required_endpoints, endpoint_counts)
            })
        if business_success is not None and len(endpoint_success_counts) == required_endpoint_count:
            endpoint_success_total = sum(endpoint_success_counts)
            if endpoint_success_total != business_success:
                self.failures.append(
                    "endpoint business_success counts must sum to global business_success "
                    f"({endpoint_success_total} != {business_success})"
                )

    def _verify_server_arrivals(
        self,
        manifest: dict[str, Any],
        scenario: str,
        endpoint_sent_total: float,
        endpoint_counts: dict[str, float],
    ) -> None:
        arrivals = manifest.get("server_arrivals")
        if arrivals is None:
            return
        if not isinstance(arrivals, dict):
            self.failures.append("manifest.server_arrivals must be an object when present")
            return
        known_endpoints = {"me", "today", "complete"}
        profile_endpoints = REQUIRED_ENDPOINTS.get(scenario, set())
        observed_total = 0.0
        for endpoint in known_endpoints:
            value = arrivals.get(endpoint, 0)
            count = self._finite_number(
                value,
                f"manifest.server_arrivals.{endpoint}",
                positive=False,
            )
            if count is None:
                continue
            if endpoint not in profile_endpoints and count != 0:
                self.failures.append(
                    f"manifest.server_arrivals.{endpoint} must be zero for {scenario}, got {count}"
                )
            if endpoint in profile_endpoints:
                expected = endpoint_counts.get(endpoint)
                if expected is not None and count != expected:
                    self.failures.append(
                        f"manifest.server_arrivals.{endpoint} must match endpoint sent_requests ({count} != {expected})"
                    )
                observed_total += count
        if observed_total != endpoint_sent_total:
            self.failures.append(
                f"manifest.server_arrivals profile total must match sent_requests ({observed_total} != {endpoint_sent_total})"
            )

    def _verify_endpoint(self, metrics: dict[str, Any], scenario: str, endpoint: str) -> tuple[float | None, float | None]:
        sent_metric = self._find_tagged_metric(metrics, "sent_requests", endpoint)
        endpoint_sent = None
        if sent_metric is None:
            self.failures.append(f"missing endpoint sent_requests for {endpoint}")
        else:
            endpoint_sent = self._number_from_values(
                sent_metric,
                f"sent_requests{{endpoint:{endpoint}}}",
                ["count"],
            )

        success_count_metric = self._find_tagged_metric(metrics, "business_success", endpoint)
        endpoint_success = None
        if success_count_metric is None:
            self.failures.append(f"missing endpoint business_success count for {endpoint}")
        else:
            endpoint_success = self._number_from_values(
                success_count_metric,
                f"business_success{{endpoint:{endpoint}}}",
                ["count"],
                allow_zero=scenario == "read",
            )

        success_rate = self._find_tagged_metric(metrics, "business_success_rate", endpoint)
        if success_rate is None and scenario != "contention":
            self.failures.append(f"missing endpoint business_success_rate for {endpoint}")
        elif success_rate is not None and scenario != "contention":
            value = self._number_from_values(
                success_rate,
                f"business_success_rate{{endpoint:{endpoint}}}",
                ["rate", "value"],
            )
            if value is not None and value < MIN_BUSINESS_SUCCESS_RATE:
                self.failures.append(
                    f"endpoint {endpoint} business_success_rate below "
                    f"{MIN_BUSINESS_SUCCESS_RATE}: {value}"
                )

        valid_rate = self._find_tagged_metric(metrics, "valid_outcome_rate", endpoint)
        if valid_rate is None:
            self.failures.append(f"missing endpoint valid_outcome_rate for {endpoint}")
        else:
            value = self._number_from_values(
                valid_rate,
                f"valid_outcome_rate{{endpoint:{endpoint}}}",
                ["rate", "value"],
            )
            if value is not None and value < MIN_BUSINESS_SUCCESS_RATE:
                self.failures.append(
                    f"endpoint {endpoint} valid_outcome_rate below "
                    f"{MIN_BUSINESS_SUCCESS_RATE}: {value}"
                )

        latency_metric = self._find_tagged_metric(metrics, "response_latency", endpoint)
        if latency_metric is None:
            self.failures.append(f"missing endpoint response_latency stats for {endpoint}")
            return endpoint_sent, endpoint_success
        values = latency_metric.get("values")
        if values is None and isinstance(latency_metric, dict):
            values = latency_metric
        if not isinstance(values, dict):
            self.failures.append(f"endpoint latency metric for {endpoint} missing values")
            return endpoint_sent, endpoint_success
        for stat, limit in LATENCY_LIMITS_MS[scenario].items():
            value = self._finite_number(
                values.get(stat),
                f"latency {endpoint} {stat}",
                positive=False,
            )
            if value is not None and value > limit:
                self.failures.append(f"endpoint {endpoint} latency {stat} {value}ms > {limit}ms")
        return endpoint_sent, endpoint_success

    def _verify_telemetry(self) -> None:
        path = self.run_dir / "telemetry.jsonl"
        if not path.is_file():
            self.failures.append("missing required artifact: telemetry.jsonl")
            self.measurement_limits.append("generator samples missing; capacity is unverified")
            self.telemetry_unverified = True
            return

        samples = 0
        generator_samples = 0
        saturated_samples = 0
        try:
            with path.open("r", encoding="utf-8") as handle:
                for line_number, line in enumerate(handle, 1):
                    if not line.strip():
                        continue
                    samples += 1
                    try:
                        sample = json.loads(line)
                    except json.JSONDecodeError as exc:
                        self.failures.append(f"telemetry.jsonl line {line_number} invalid JSON: {exc}")
                        continue
                    generator = sample.get("generator")
                    if isinstance(generator, dict):
                        rss_kib = self._finite_number(
                            generator.get("rss_kib"),
                            f"telemetry line {line_number} generator.rss_kib",
                            positive=True,
                            allow_zero=False,
                        )
                        self._finite_number(
                            generator.get("cpu_percent"),
                            f"telemetry line {line_number} generator.cpu_percent",
                            positive=False,
                        )
                        if rss_kib is not None:
                            generator_samples += 1
                        cpu_percent = generator.get("cpu_percent")
                        if isinstance(cpu_percent, (int, float)) and not isinstance(cpu_percent, bool) and cpu_percent >= 95:
                            saturated_samples += 1
                    if not isinstance(sample.get("containers"), list):
                        self.measurement_limits.append(
                            f"telemetry line {line_number} missing container stats"
                        )
                        self.telemetry_unverified = True
                    if not isinstance(sample.get("jvm"), dict):
                        self.measurement_limits.append(f"telemetry line {line_number} missing JVM stats")
                        self.telemetry_unverified = True
        except OSError as exc:
            self.failures.append(f"cannot read telemetry.jsonl: {exc}")
            return

        self.details["telemetry_samples"] = samples
        self.details["generator_samples"] = generator_samples
        if samples == 0 or generator_samples == 0:
            self.failures.append("telemetry.jsonl has no generator samples")
            self.measurement_limits.append("generator samples missing; capacity is unverified")
            self.telemetry_unverified = True
        elif generator_samples < MIN_GENERATOR_SAMPLES_FOR_CAPACITY:
            self.measurement_limits.append(
                f"only {generator_samples} generator samples; capacity claim is exploratory"
            )
            self.telemetry_unverified = True
        if saturated_samples:
            self.measurement_limits.append(
                f"generator CPU near saturation in {saturated_samples} telemetry samples; capacity is unverified"
            )
            self.telemetry_unverified = True

    def _verify_container_evidence(self, manifest: dict[str, Any]) -> None:
        containers = manifest.get("containers")
        if not isinstance(containers, dict):
            self.measurement_limits.append("manifest.containers missing; container provenance is incomplete")
            self.telemetry_unverified = True
            return
        for name in REQUIRED_CONTAINER_NAMES:
            container = containers.get(name)
            if not isinstance(container, dict):
                self.measurement_limits.append(f"manifest.containers.{name} missing; container provenance is incomplete")
                self.telemetry_unverified = True
                continue
            for field_name in REQUIRED_CONTAINER_FIELDS:
                if field_name not in container:
                    self.measurement_limits.append(
                        f"manifest.containers.{name}.{field_name} missing; container provenance is incomplete"
                    )
                    self.telemetry_unverified = True
            self._finite_number(
                container.get("memory_bytes"),
                f"manifest.containers.{name}.memory_bytes",
                positive=True,
                allow_zero=False,
            )
            self._finite_number(
                container.get("nano_cpus"),
                f"manifest.containers.{name}.nano_cpus",
                positive=True,
                allow_zero=False,
            )
            image_id = container.get("image_id")
            if not isinstance(image_id, str) or not image_id.startswith("sha256:"):
                self.measurement_limits.append(
                    f"manifest.containers.{name}.image_id must be a sha256 digest"
                )
                self.telemetry_unverified = True
            image = container.get("image")
            if not isinstance(image, dict):
                self.measurement_limits.append(f"manifest.containers.{name}.image missing; container image evidence is incomplete")
                self.telemetry_unverified = True
                continue
            architecture = image.get("architecture")
            if not isinstance(architecture, str) or not architecture:
                self.measurement_limits.append(
                    f"manifest.containers.{name}.image.architecture missing; container image evidence is incomplete"
                )
                self.telemetry_unverified = True
            repo_digests = image.get("repo_digests")
            if not isinstance(repo_digests, list) or not any(
                isinstance(value, str) and "@sha256:" in value for value in repo_digests
            ):
                self.measurement_limits.append(
                    f"manifest.containers.{name}.image.repo_digests missing sha256 digest"
                )
                self.telemetry_unverified = True

    def _verify_db_audit(self, audit: dict[str, Any]) -> None:
        audit_passed = audit.get("passed") is True
        if not audit_passed:
            self.failures.append("db-audit.passed must be true")
        violations = audit.get("violations")
        if not isinstance(violations, list):
            self.failures.append("db-audit.violations must be a list")
        elif violations:
            self.failures.append(f"db-audit has violations: {violations}")

        date = audit.get("date")
        if not isinstance(date, str) or not date:
            self.failures.append("db-audit.date must be a non-empty string")
        completion_audit = audit.get("completion_audit")
        reward_audit = audit.get("reward_audit")
        checked_tables = audit.get("checked_tables")
        if not isinstance(checked_tables, list) or not checked_tables:
            self.failures.append("db-audit.checked_tables must be a non-empty list")
        if not isinstance(completion_audit, dict):
            self.failures.append("db-audit.completion_audit must be an object")
            completion_audit = None
        if isinstance(completion_audit, dict):
            for name in (
                "expected_unique_completions",
                "actual_completed_todos",
                "completed_outside_expected_prefix",
                "missing_expected_prefix_completion",
                "owner_mismatch",
            ):
                self._audit_number(completion_audit.get(name), f"db-audit.completion_audit.{name}", audit_passed)
            expected_unique = completion_audit.get("expected_unique_completions")
            actual_completed = completion_audit.get("actual_completed_todos")
            if (
                expected_unique is not None
                and actual_completed is not None
                and expected_unique != actual_completed
            ):
                self.failures.append("db-audit completed todo count differs from expected unique completions")
            completed_outside = completion_audit.get("completed_outside_expected_prefix")
            missing_prefix = completion_audit.get("missing_expected_prefix_completion")
            if completed_outside is not None and completed_outside != 0:
                self.failures.append("db-audit found completions outside expected prefix")
            if missing_prefix is not None and missing_prefix != 0:
                self.failures.append("db-audit found missing expected prefix completions")
            if completion_audit.get("owner_mismatch") != 0:
                self.failures.append("db-audit found todo owner mismatches")

        if not isinstance(reward_audit, dict):
            self.failures.append("db-audit.reward_audit must be an object")
            reward_audit = None
        if isinstance(reward_audit, dict):
            for name in (
                "expected_reward_total",
                "actual_todo_reward_total",
                "duplicate_history_sources",
                "history_owner_swaps",
                "rewarded_users",
            ):
                self._audit_number(reward_audit.get(name), f"db-audit.reward_audit.{name}", audit_passed)
            expected_reward = reward_audit.get("expected_reward_total")
            actual_reward = reward_audit.get("actual_todo_reward_total")
            if expected_reward is not None and actual_reward is not None and expected_reward != actual_reward:
                self.failures.append("db-audit reward total differs from todo reward total")
            if reward_audit.get("duplicate_history_sources") != 0:
                self.failures.append("db-audit found duplicate wallet history sources")
            if reward_audit.get("history_owner_swaps") != 0:
                self.failures.append("db-audit found wallet history owner swaps")

    def _audit_number(self, value: Any, label: str, audit_passed: bool) -> float | None:
        if value is None and not audit_passed:
            return None
        return self._finite_number(value, label, positive=False)

    def _metric_number(
        self,
        metrics: dict[str, Any],
        metric_name: str,
        keys: list[str],
        *,
        allow_zero: bool = False,
    ) -> float | None:
        metric = metrics.get(metric_name)
        if not isinstance(metric, dict):
            return None
        return self._number_from_values(metric, metric_name, keys, allow_zero=allow_zero)

    def _number_from_values(
        self,
        metric: dict[str, Any],
        metric_name: str,
        keys: list[str],
        *,
        allow_zero: bool = False,
    ) -> float | None:
        values = metric.get("values")
        if values is None:
            values = metric
        if not isinstance(values, dict):
            self.failures.append(f"{metric_name}.values must be an object")
            return None
        for key in keys:
            if key in values:
                return self._finite_number(
                    values.get(key),
                    f"{metric_name}.values.{key}",
                    positive=not allow_zero,
                    allow_zero=allow_zero,
                )
        self.failures.append(f"{metric_name}.values missing one of: {', '.join(keys)}")
        return None

    def _find_tagged_metric(
        self,
        metrics: dict[str, Any],
        base_name: str,
        endpoint: str,
    ) -> dict[str, Any] | None:
        exact_names = (
            f"{base_name}{{endpoint:{endpoint}}}",
            f"{base_name}{{endpoint:{endpoint.replace('_', '/')}}}",
            f"{base_name}{{name:{endpoint}}}",
            f"{base_name}{{endpoint:{endpoint},scenario:normal_capacity}}",
        )
        for name in exact_names:
            metric = metrics.get(name)
            if isinstance(metric, dict):
                return metric
        prefix = f"{base_name}{{"
        for name, metric in metrics.items():
            if (
                isinstance(name, str)
                and name.startswith(prefix)
                and isinstance(metric, dict)
                and self._tagged_endpoint_matches(name, endpoint)
            ):
                return metric
        return None

    def _tagged_endpoint_matches(self, metric_name: str, endpoint: str) -> bool:
        expected_values = {endpoint, endpoint.replace("_", "/")}
        tag_body = metric_name.split("{", 1)[1].rsplit("}", 1)[0]
        tags = {}
        for raw_tag in tag_body.split(","):
            if ":" not in raw_tag:
                continue
            key, value = raw_tag.split(":", 1)
            tags[key.strip()] = value.strip()
        return tags.get("endpoint") in expected_values or tags.get("name") in expected_values

    def _finite_number(
        self,
        value: Any,
        label: str,
        *,
        positive: bool,
        allow_zero: bool = True,
    ) -> float | None:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            self.failures.append(f"{label} must be numeric")
            return None
        number = float(value)
        if not math.isfinite(number):
            self.failures.append(f"{label} must be finite")
            return None
        if positive and number < 0:
            self.failures.append(f"{label} must be positive")
            return None
        if positive and not allow_zero and number == 0:
            self.failures.append(f"{label} must be greater than zero")
            return None
        return number

    def _manifest_exit_code_nonzero(self, manifest: dict[str, Any] | None) -> bool:
        return isinstance(manifest, dict) and isinstance(manifest.get("k6_exit_code"), int) and manifest["k6_exit_code"] != 0

    def _classification(self, passed: bool, scenario: Any) -> str:
        if scenario == "contention":
            return "diagnostic" if passed else "diagnostic_failed"
        if self.telemetry_unverified:
            return "capacity_unverified"
        if any("capacity is unverified" in item for item in self.measurement_limits):
            return "capacity_unverified"
        return "capacity" if passed else "failed"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path, help="Directory containing manifest.json, summary.json, and db-audit.json")
    args = parser.parse_args(argv)

    verifier = Verifier(args.run_dir)
    verdict = verifier.verify()
    verdict_path = args.run_dir / "verdict.json"
    verdict_path.write_text(json.dumps(verdict, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(verdict, ensure_ascii=False, indent=2))
    return 0 if verdict["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
