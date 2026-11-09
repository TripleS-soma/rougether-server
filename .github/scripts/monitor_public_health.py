"""배포 중 공개 health 응답과 연결 오류를 JSONL로 관측함. 배포 판정과 독립적으로 실행함."""

import argparse
from contextlib import suppress
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import signal
import threading
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import urlopen


def target(value):
    name, separator, url = value.partition("=")
    parsed = urlsplit(url)
    if (not separator or not re.fullmatch(r"[A-Za-z0-9_-]+", name)
            or parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment):
        raise argparse.ArgumentTypeError("target은 자격증명과 query가 없는 name=http(s)://host/path 형식이어야 함")
    return name, url


def positive(value):
    number = float(value)
    if not 0 < number < float("inf"):
        raise argparse.ArgumentTypeError("유한한 양수가 필요함")
    return number


def error_details(error):
    reason = getattr(error, "reason", None)
    if reason is None:
        reason = error
    number = getattr(reason, "errno", None)
    if number is None:
        number = getattr(error, "errno", None)
    return {"error": type(error).__name__, "errorMessage": str(error),
            "reasonType": type(reason).__name__, "reason": str(reason), "errno": number}


def probe(endpoint, timeout):
    name, url = endpoint
    started = time.monotonic()
    result = {"service": name, "at": datetime.now(timezone.utc).isoformat(),
              "code": None, "status": None, "healthy": False}
    stage = "open_response"
    try:
        with urlopen(url, timeout=timeout) as response:
            result["code"] = response.status
            result["cloudFrontRequestId"] = response.headers.get("x-amz-cf-id")
            stage = "read_body"
            body = response.read(65537)
            if len(body) > 65536:
                raise ValueError("health 응답이 64KiB를 초과함")
            stage = "parse_body"
            document = json.loads(body)
            result["status"] = document.get("status")
            result["healthy"] = response.status == 200 and str(result["status"]).lower() in {"ok", "up"}
    except Exception as error:
        if isinstance(error, HTTPError):
            result["code"] = error.code
            result["cloudFrontRequestId"] = error.headers.get("x-amz-cf-id") if error.headers else None
            stage = "http_response"
            # 응답 정리 실패가 원래 HTTP 오류 기록을 덮지 않게 함.
            with suppress(Exception):
                error.close()
        result.update(error_details(error), failureStage=stage)
    result["elapsedMs"] = round((time.monotonic() - started) * 1000, 1)
    return result


def monitor(endpoints, output, summary, stop_file, duration, interval, timeout, stop_event):
    output.parent.mkdir(parents=True, exist_ok=True)
    summary.parent.mkdir(parents=True, exist_ok=True)
    totals = {name: {"probes": 0, "healthy": 0, "httpErrors": 0, "connectionFailures": 0,
                     "otherFailures": 0} for name, _ in endpoints}
    deadline = time.monotonic() + duration
    try:
        with ThreadPoolExecutor(max_workers=len(endpoints)) as pool, output.open("w") as stream:
            while not stop_event.is_set() and not stop_file.exists() and time.monotonic() < deadline:
                for row in pool.map(lambda endpoint: probe(endpoint, timeout), endpoints):
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
                    stream.flush()
                    counts = totals[row["service"]]
                    counts["probes"] += 1
                    if row["healthy"]:
                        counts["healthy"] += 1
                    elif row["code"] is None:
                        counts["connectionFailures"] += 1
                    elif row["code"] >= 400:
                        counts["httpErrors"] += 1
                    else:
                        counts["otherFailures"] += 1
                stop_event.wait(min(interval, max(0, deadline - time.monotonic())))
    finally:
        # SIGTERM/중단 요청이나 일부 실패에도 이미 관측한 횟수를 보존함.
        with tempfile.NamedTemporaryFile(mode="w", dir=summary.parent, delete=False) as stream:
            json.dump({"services": totals, "diagnosticOnly": True}, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            temporary = Path(stream.name)
        temporary.replace(summary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=target, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--stop-file", type=Path, required=True)
    parser.add_argument("--duration", type=positive, default=6600)
    parser.add_argument("--interval", type=positive, default=2)
    parser.add_argument("--timeout", type=positive, default=5)
    args = parser.parse_args(argv)
    if len({name for name, _ in args.target}) != len(args.target):
        parser.error("target 이름은 중복될 수 없음")
    if len({args.output.resolve(), args.summary.resolve(), args.stop_file.resolve()}) != 3:
        parser.error("output, summary, stop-file은 서로 달라야 함")
    stop = threading.Event()
    for kind in (signal.SIGTERM, signal.SIGINT):
        signal.signal(kind, lambda _signum, _frame: stop.set())
    monitor(args.target, args.output, args.summary, args.stop_file,
            args.duration, args.interval, args.timeout, stop)


if __name__ == "__main__":
    main()
