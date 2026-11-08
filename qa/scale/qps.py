"""부하 구간의 MySQL 전역 Questions 증가율. 샘플러와 독립적으로 두 번만 읽는다."""

from datetime import datetime, timezone
import math
import time

STATUS_SQL = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Uptime','Uptime_since_flush_status')"


def sample(query):
    server = str(query("SELECT @@server_uuid")[0][0])
    start = time.monotonic()
    values = {str(k): int(v) for k, v in query(STATUS_SQL)}
    end = time.monotonic()
    return {
        "utc": datetime.now(timezone.utc).isoformat(),
        "monotonic_s": (start + end) / 2,
        "read_s": end - start,
        "server_uuid": server,
        "questions": values["Questions"],
        "uptime_s": values["Uptime"],
        "since_flush_s": values["Uptime_since_flush_status"],
    }


def rate(first, last):
    for snapshot in (first, last):
        if not snapshot["server_uuid"] or any(
            not math.isfinite(snapshot[key]) or snapshot[key] < 0
            for key in (
                "monotonic_s",
                "read_s",
                "questions",
                "uptime_s",
                "since_flush_s",
            )
        ):
            raise ValueError("유효하지 않은 QPS 카운터 표본")
    elapsed = last["monotonic_s"] - first["monotonic_s"]
    delta = last["questions"] - first["questions"]
    tolerance = 2 + first["read_s"] + last["read_s"]
    if (
        elapsed <= 0
        or delta < 0
        or first["server_uuid"] != last["server_uuid"]
        or any(
            abs(last[key] - first[key] - elapsed) > tolerance
            for key in ("uptime_s", "since_flush_s")
        )
    ):
        raise ValueError(
            "DB 재시작, 상태 초기화 또는 시각 불일치로 QPS를 계산할 수 없습니다."
        )
    return {
        "before": first,
        "after": last,
        "questions_delta": delta,
        "elapsed_s": elapsed,
        "questions_per_second": delta / elapsed,
    }
