"""Report worst endpoint-level 1-second percentiles in complete minute-boundary windows."""
import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path


def measure(directory):
    diag = json.loads((directory / "diagnostics.json").read_text())
    config = json.loads((directory / "summary.json").read_text())["config"]
    start = datetime.fromisoformat(diag["load_started_at"]).timestamp()
    end = start + config["durationSeconds"]
    buckets = {b["epoch_second"]: b["endpoints"] for b in diag["k6"]["buckets"]}
    endpoints = ("me", "today", "complete")
    windows = []
    for minute in range(math.ceil((start + 30) / 60) * 60, math.floor(end - 3) + 1, 60):
        # Missing one second or route invalidates the window, rather than lowering its maximum.
        values = [buckets[minute + offset][ep]["latency_ms"] for offset in range(3) for ep in endpoints]
        windows.append({
            "utc": datetime.fromtimestamp(minute, timezone.utc).isoformat(),
            "max_endpoint_second_p95_ms": round(max(v["p95"] for v in values), 2),
            "max_endpoint_second_p99_ms": round(max(v["p99"] for v in values), 2),
        })
    if not windows:
        raise ValueError("no complete minute window after the first 30 seconds")
    return windows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", type=Path)
    print(json.dumps(measure(parser.parse_args().run_directory), indent=2))
