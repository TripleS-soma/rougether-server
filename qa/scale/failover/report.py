"""저장한 요청, ALB 관측, DB 원장을 대조해 공개 가능한 집계만 출력한다."""

import argparse
from collections import Counter
import gzip
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import qps


def summarize(rows):
    durations = sorted(row["latency_ms"] for row in rows)

    def percentile(fraction):
        return (
            round(durations[math.ceil(len(durations) * fraction) - 1], 2)
            if durations
            else None
        )

    return {
        "http": len(rows),
        "first": sum(row["attempt"] == 1 for row in rows),
        "retry": sum(row["attempt"] == 2 for row in rows),
        "status": dict(sorted(Counter(row["status"] for row in rows).items())),
        "unexpected": sum(
            not (row["ok"] or row["attempt"] == 2 and row["duplicate"]) for row in rows
        ),
        "expected_409": sum(row["attempt"] == 2 and row["duplicate"] for row in rows),
        "transport_error": sum(row["status"] == 0 for row in rows),
        "http_5xx": sum(500 <= row["status"] < 600 for row in rows),
        "p95_ms": percentile(0.95),
        "p99_ms": percentile(0.99),
    }


def analyze(directory):
    def read(name):
        return json.loads((directory / name).read_text())

    timeline = read("timeline.json")
    result = read("result.json")
    with gzip.open(directory / "requests.json.gz", "rt") as f:
        rows = json.load(f)
    stop = timeline["stop_sent"]
    healthy = timeline["replacement_healthy_at"]
    phases = {
        "before_stop": [r for r in rows if r["start"] < stop],
        "during_recovery": [r for r in rows if stop <= r["start"] < healthy],
        "after_recovery": [r for r in rows if healthy <= r["start"]],
    }
    latencies = {
        key: round(timeline[key] - stop, 3)
        for key in (
            "stop_ack",
            "victim_unhealthy_or_draining_at",
            "victim_absent_at",
            "replacement_running_at",
            "replacement_healthy_at",
            "finish_sent",
        )
        if key in timeline
    }
    drain = timeline["victim_unhealthy_or_draining_at"]
    single_target_rows = [r for r in rows if drain <= r["start"] < healthy]
    target_states = [
        point["targets"]
        for point in timeline["timeline"]
        if drain <= point["at"] < healthy
    ]
    reads = [r for r in rows if r["endpoint"] != "complete"]
    writes = [r for r in rows if r["endpoint"] == "complete" and r["attempt"] == 1]
    retries = [r for r in rows if r["attempt"] == 2]
    first = [r for r in rows if r["attempt"] == 1]
    ordered = sorted(r["start"] for r in first)
    gaps = [b - a for a, b in zip(ordered, ordered[1:])]
    qp = result["qps"]
    audit = result["db_audit"]
    state = read(next(directory.glob("api-loss-*.json")).name)
    assertions = {
        "survivor_was_only_healthy_target": bool(single_target_rows)
        and bool(target_states)
        and all(
            [ip for ip, status in snapshot.items() if status == "healthy"]
            == [timeline["survivor_ip"]]
            for snapshot in target_states
        ),
        "qps_matches_counter": qps.rate(qp["before"], qp["after"]) == qp
        and qp["questions_delta"] > 0,
        "traffic_in_all_phases": all(phases.values())
        and bool(gaps)
        and max(gaps) < 1
        and not result["capped"],
        "http_counts_match": len(rows) == result["http_count"]
        and len(first) == result["logical_count"]
        and sorted(r["seq"] for r in first) == list(range(len(first))),
        "one_replay_per_write": bool(writes)
        and len({r["todo"] for r in writes}) == len(writes)
        and Counter((r["seq"], r["todo"]) for r in writes)
        == Counter((r["seq"], r["todo"]) for r in retries),
        "all_rewards_correct": len(writes)
        == audit["expected"]
        == audit["checked"]
        == audit["correct"]
        and audit["outside_ledger"] == 0,
        "cleanup": all(
            state.get(k)
            for k in (
                "original_service",
                "artifacts_removed",
                "schema_removed",
                "stopped_tasks",
            )
        )
        and not state["cleanup_errors"],
    }
    return dict(
        checks=assertions,
        stop_to_event_seconds=latencies,
        total=summarize(rows),
        phases={key: summarize(group) for key, group in phases.items()},
        single_healthy_target=summarize(single_target_rows),
        reads=summarize(reads),
        writes=summarize(writes),
        retries=summarize(retries),
        db_audit=audit,
        measurement_seconds=qp["elapsed_s"],
        questions_delta=qp["questions_delta"],
        db_qps=qp["questions_per_second"],
        http_rps=len(rows) / qp["elapsed_s"],
        confirmed_todo_tps=audit["correct"] / qp["elapsed_s"],
        max_primary_gap_ms=round(max(gaps) * 1000, 2),
    )


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("directory", type=Path)
    s = analyze(cli.parse_args().directory)
    print(json.dumps(s, indent=2))
    raise SystemExit(0 if all(s["checks"].values()) else 1)
