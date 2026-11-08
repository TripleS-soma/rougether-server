"""Recompute rates from retained measurements. No AWS access or load generation."""

import argparse
import csv
import json
import re
from datetime import datetime
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
PATTERN = re.compile(r"n(\d+)-r(1000|1500)-rep\d+$")


def read(path):
    return json.loads(path.read_text())


def ts(value):
    return datetime.fromisoformat(value).timestamp()


def path_label(path):
    return (
        str(path.relative_to(ROOT))
        if path.is_relative_to(ROOT)
        else f"archive/{path.name}"
    )


def db_rates(diag, seconds):
    if not diag.get("load_started_at"):
        return {}
    start = ts(diag["load_started_at"])
    points = []
    for sample in diag.get("samples", []):
        snap = sample.get("db") or {}
        stat = snap.get("global_status") or {}
        vals = stat.get("value") or {}
        if stat.get("status") != "ok" or "Questions" not in vals:
            continue
        when = ts(snap["at"])
        if start <= when < start + seconds:
            points.append((when, vals))
    if len(points) < 2:
        return {}
    first, last = points[0], points[-1]
    elapsed = last[0] - first[0]
    for (t0, a), (t1, b) in zip(points, points[1:]):
        assert t1 > t0 and b["Questions"] >= a["Questions"]
    n = last[1]["Questions"] - first[1]["Questions"]
    return dict(
        db_samples=len(points),
        db_window_s=elapsed,
        db_first_at=datetime.fromtimestamp(first[0]).astimezone().isoformat(),
        db_last_at=datetime.fromtimestamp(last[0]).astimezone().isoformat(),
        first_questions=first[1]["Questions"],
        last_questions=last[1]["Questions"],
        questions_delta=n,
        db_qps=n / elapsed,
        explicit_commits_per_s=(last[1]["Com_commit"] - first[1]["Com_commit"])
        / elapsed,
    )


def k6(path):
    folder = path.parent
    nodes, rate = map(int, PATTERN.fullmatch(folder.name).groups())
    s = read(path)
    metrics = s.get("metrics") or {}
    row = dict(run=path_label(folder), target_rps=rate, nodes=nodes)
    if not metrics.get("http_reqs"):
        return dict(row, status="no_main_load")
    d = read(folder / "diagnostics.json")
    a = read(folder / "db-audit.json")
    h = metrics["http_reqs"]
    elapsed = h["count"] / h["rate"]
    written = metrics["completion_success{endpoint:complete}"]
    db_count = a["completion_audit"]["actual_completed_todos"]
    row.update(
        status=read(folder / "verdict.json")["classification"],
        http_count=h["count"],
        duration_s=elapsed,
        actual_rps=h["rate"],
        dropped=(metrics.get("dropped_iterations") or {}).get("count", 0),
        success_requests=metrics["business_success"]["count"],
        success_requests_per_s=metrics["business_success"]["count"] / elapsed,
        todo_http_success=written["count"],
        todo_http_tps=written["count"] / elapsed,
        db_completed=db_count,
        db_todo_tps=db_count / elapsed,
        db_audit_pass=a["passed"],
    )
    assert abs(row["todo_http_tps"] - written["rate"]) < 1e-6
    row.update(db_rates(d, s["config"]["durationSeconds"]))
    return row


def mobile(path):
    a = read(path)
    if not 990 <= a.get("rps", 0) <= 1010:
        return None
    statuses = {a["routes"][int(i)]: v for i, v in a["status"].items()}
    assert sum(sum(v.values()) for v in statuses.values()) == a["issued"]
    rows = []
    for route, counts in statuses.items():
        accepted = sum(n for code, n in counts.items() if int(code) // 100 == 2)
        rows.append(
            dict(
                run=path_label(path),
                route=route,
                count=sum(counts.values()),
                http_2xx=accepted,
                http_2xx_per_s=accepted / a["seconds"],
                duration_s=a["seconds"],
            )
        )
    return rows


def write_csv(filename, rows):
    if not rows:
        raise SystemExit(f"{filename}: 집계할 실험 원본을 찾지 못했습니다.")
    with (OUT / filename).open("w", newline="") as f:
        w = csv.DictWriter(
            f,
            fieldnames=list(dict.fromkeys(k for r in rows for k in r)),
            lineterminator="\n",
        )
        w.writeheader()
        for r in rows:
            w.writerow(
                {k: round(v, 6) if isinstance(v, float) else v for k, v in r.items()}
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--archive", type=Path, help="additional ecs-scale/results directory"
    )
    args = parser.parse_args()
    roots = [ROOT / "qa"] + ([args.archive] if args.archive else [])
    paths = sorted(
        {
            p.resolve()
            for base in roots
            for p in base.rglob("summary.json")
            if PATTERN.fullmatch(p.parent.name)
            and (p.parent / "diagnostics.json").exists()
        }
    )
    rows = [k6(p) for p in paths]
    write_csv("k6-rates.csv", rows)
    mobile_rows = [
        r
        for p in sorted((ROOT / "output/benchmarks").rglob("*-peak.json"))
        for r in (mobile(p) or [])
    ]
    write_csv("mobile-rates.csv", mobile_rows)
    print(
        f"{len(rows)} k6 run records, {sum(bool(r.get('db_samples')) for r in rows)} with QPS; {len({r['run'] for r in mobile_rows})} mobile runs."
    )
