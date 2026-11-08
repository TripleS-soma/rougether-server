"""두 태스크의 ALB 경유 연속 트래픽. 완료 후 같은 투두로 1회 재시도하고 원장을 감사한다."""

import concurrent.futures
import gzip
import http.client
import json
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from urllib.parse import urlsplit

import qps
import seed_scale_db as fixture
from mint_test_jwts import mint

RATE = 50  # 논리 요청/초. 다섯 번째는 쓰기 후 재시도하므로 HTTP는 약 60 RPS.
LIMIT = 720
USERS = RATE * LIMIT // 5
U = fixture.DEFAULT_USER_START
T = fixture.DEFAULT_TODO_START
local = threading.local()


def seed(db):
    kst = timezone(timedelta(hours=9))
    now = datetime.now(kst)
    args = SimpleNamespace(
        users=USERS,
        todo_count=USERS,
        user_start=U,
        todo_start=T,
        wallet_start=fixture.DEFAULT_WALLET_START,
        date=now.date().isoformat(),
    )
    tables = [
        (
            "users",
            "id,email,nickname,created_at,updated_at,deleted_at,is_bot,bot_key",
            fixture.iter_user_rows,
        ),
        (
            "user_wallets",
            "id,user_id,currency_type,balance,created_at,updated_at",
            fixture.iter_wallet_rows,
        ),
        (
            "personal_rooms",
            "user_id,growth_level,updated_at,layout_format,layout_revision,growth_points,highest_growth_level",
            fixture.iter_room_rows,
        ),
        (
            "todos",
            "id,user_id,category_id,title,description,due_date,due_time,status,completed_at,reward_currency_type,reward_amount,created_at,updated_at,deleted_at,external_source,external_id,growth_reward_amount",
            fixture.iter_todo_rows,
        ),
    ]
    with db() as conn, conn.cursor() as cur:
        for table, columns, generate in tables:
            rows = generate(args, now.strftime("%Y-%m-%d %H:%M:%S"))
            for batch in fixture.batched(rows, 500):
                cur.executemany(
                    f"INSERT INTO {table} ({columns}) VALUES ("
                    + ",".join(["%s"] * len(batch[0]))
                    + ")",
                    batch,
                )


def request(endpoint, user, todo, seq, attempt):
    origin = urlsplit(os.environ["BASE_URL"])
    if not getattr(local, "conn", None):
        local.conn = http.client.HTTPConnection(
            origin.hostname, origin.port or 80, timeout=4
        )
    path = {
        "me": "/api/v1/me",
        "today": "/api/v1/today",
        "complete": f"/api/v1/todos/{todo}/complete",
    }[endpoint]
    start = time.time()
    clock = time.monotonic()
    try:
        local.conn.request(
            "POST" if endpoint == "complete" else "GET",
            path,
            headers={
                "Authorization": "Bearer "
                + mint(user, os.environ["QA_JWT"], int(time.time()), 3600)
            },
        )
        res = local.conn.getresponse()
        data = res.read()
        try:
            body = json.loads(data)
        except (ValueError, UnicodeError):
            body = {}
        if not isinstance(body, dict):
            body = {}
        status, code = res.status, body.get("code")
        ok = status == (201 if endpoint == "complete" else 200) and (
            endpoint == "me"
            and body.get("userId") == user
            or endpoint == "today"
            and isinstance(body.get("categories"), list)
            or endpoint == "complete"
            and body.get("id") == todo
            and body.get("status") == "COMPLETED"
            and isinstance(body.get("completedAt"), str)
            and body.get("rewardAmount") == fixture.REWARD_AMOUNT
        )
        duplicate = (
            endpoint == "complete"
            and status == 409
            and code == "TODO_ALREADY_COMPLETED"
        )
    except (OSError, http.client.HTTPException) as exc:
        local.conn.close()
        local.conn = None
        status, code, ok, duplicate = 0, type(exc).__name__, False, False
    return dict(
        start=start,
        latency_ms=1000 * (time.monotonic() - clock),
        endpoint=endpoint,
        seq=seq,
        todo=todo,
        attempt=attempt,
        status=status,
        code=code,
        ok=ok,
        duplicate=duplicate,
    )


def logical(seq):
    if seq % 5 != 4:
        return [
            request("me" if seq % 2 == 0 else "today", U + (seq % 128), None, seq, 1)
        ]
    n = seq // 5
    first = request("complete", U + n, T + n, seq, 1)
    second = request("complete", U + n, T + n, seq, 2)
    return [first, second]


def audit(db, writes):
    with db() as conn, conn.cursor() as c:
        c.execute(
            """SELECT COUNT(*), COALESCE(SUM(t.status='COMPLETED' AND t.reward_amount=%s AND
                  t.user_id=%s+t.id-%s AND h.entries=1 AND h.total=t.reward_amount AND h.uid=t.user_id
                  AND w.balance=t.reward_amount AND r.growth_points=t.reward_amount),0)
                  FROM todos t LEFT JOIN
                  (SELECT source_id,COUNT(*) entries,SUM(amount) total,MIN(user_id) uid FROM wallet_histories
                   WHERE source_type='TODO' GROUP BY source_id) h ON h.source_id=t.id
                  LEFT JOIN user_wallets w ON w.user_id=t.user_id AND w.currency_type='COIN'
                  LEFT JOIN personal_rooms r ON r.user_id=t.user_id WHERE t.id >= %s AND t.id < %s""",
            (fixture.REWARD_AMOUNT, U, T, T, T + writes),
        )
        checked, correct = c.fetchone()
        c.execute(
            "SELECT COUNT(*) FROM wallet_histories WHERE source_type='TODO' AND source_id >= %s",
            (T + writes,),
        )
        (extra,) = c.fetchone()
        return dict(
            expected=writes,
            checked=int(checked),
            correct=int(correct),
            outside_ledger=int(extra),
        )


def run(db):
    import boto3

    s3 = boto3.client("s3")
    loc = {"Bucket": os.environ["EVIDENCE_BUCKET"]}
    prefix = os.environ["MIX_PREFIX"] + "/"

    def put(name, data):
        s3.put_object(
            **loc, Key=prefix + name, Body=data, ServerSideEncryption="AES256"
        )

    ended = threading.Event()

    def poll():
        while not ended.wait(2):
            if s3.list_objects_v2(**loc, Prefix=prefix + "finish", MaxKeys=1).get(
                "KeyCount"
            ):
                ended.set()

    with db() as conn, conn.cursor() as c:

        def query(sql):
            c.execute(sql)
            return c.fetchall()

        futures = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
            threading.Thread(target=poll, daemon=True).start()
            first = qps.sample(query)
            start = time.monotonic()
            for seq in range(RATE * LIMIT):
                if ended.is_set():
                    break
                if seq == RATE * 5:
                    receipts = [r for j in futures[:20] for r in j.result()]
                    if not all(
                        r["ok"] if r["attempt"] == 1 else r["duplicate"]
                        for r in receipts
                    ):
                        raise RuntimeError(
                            "기준 요청/재시도 본문 검증 실패: "
                            + str(
                                [
                                    (r["endpoint"], r["status"], r["code"])
                                    for r in receipts
                                    if not r["ok"] and not r["duplicate"]
                                ][:5]
                            )
                        )
                    put("ready", b"1")
                time.sleep(max(0, start + seq / RATE - time.monotonic()))
                futures.append(pool.submit(logical, seq))
            rows = [row for job in futures for row in job.result()]
            last = qps.sample(query)
        ended.set()
    writes = sum(row["endpoint"] == "complete" and row["attempt"] == 1 for row in rows)
    result = dict(
        qps=qps.rate(first, last),
        db_audit=audit(db, writes),
        logical_count=len(futures),
        http_count=len(rows),
        capped=len(futures) == RATE * LIMIT,
    )
    put(
        "requests.json.gz",
        gzip.compress(json.dumps(rows, separators=(",", ":")).encode()),
    )
    put("result.json", json.dumps(result).encode())
