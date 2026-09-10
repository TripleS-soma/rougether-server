"""누적 이력 인덱스 실험. 일회용 rougether_scale DB에서만 실행한다."""
from __future__ import annotations

import hashlib
import json
import time
from datetime import date, timedelta

from seed import audit
from seed.seed_scale_db import database_guard_sql


INDEX_SQL = """CREATE INDEX qa_todos_user_deleted_due ON todos(user_id, deleted_at, due_date);
CREATE INDEX qa_todos_user_status_completed ON todos(user_id, status, completed_at);
"""


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def history_metadata(fixture, per_user):
    if fixture["databaseName"] != "rougether_scale":
        raise ValueError("isolated rougether_scale database required")
    users = int(fixture["usersCount"])
    if not (1 <= users <= 1000000 and 0 <= per_user <= 1000 and users * per_user <= 20000000):
        raise ValueError("history fixture exceeds bounded experiment size")
    seed_date = date.fromisoformat(fixture["date"])
    start = int(fixture["todoStartId"]) + int(fixture["todoCount"])
    return {"per_user": per_user, "count": users * per_user,
            "start_id": start, "end_id": start + users * per_user - 1,
            "seed_date": seed_date.isoformat(),
            "model": "past overdue completed todos; reward and growth zero; outside active ID range"}


def history_sql(fixture, history):
    users = int(fixture["usersCount"])
    user_start = int(fixture["userStartId"])
    seed_date = date.fromisoformat(history["seed_date"])
    statements = [database_guard_sql("rougether_scale"), "SET time_zone = '+00:00';"]
    for slot in range(history["per_user"]):
        due = seed_date - timedelta(days=history["per_user"] + 30 - slot)
        completed = due + timedelta(days=1)
        start = history["start_id"] + slot * users
        statements.append(
            "INSERT INTO todos (id,user_id,title,due_date,status,completed_at,"
            "reward_currency_type,reward_amount,growth_reward_amount,created_at,updated_at) "
            f"SELECT {start} + (id - {user_start}), id, CONCAT('scale-history-{slot}-',id), "
            f"'{due}', 'COMPLETED', '{completed} 12:00:00', 'COIN', 0, 0, "
            f"'{due} 12:00:00', '{completed} 12:00:00' FROM users "
            f"WHERE id BETWEEN {user_start} AND {user_start + users - 1};")
    return "\n".join(statements) + "\n"


def history_snapshot(mysql, fixture, history):
    start, end = history["start_id"], history["end_id"]
    users, user_start = int(fixture["usersCount"]), int(fixture["userStartId"])
    due = (f"DATE_SUB('{history['seed_date']}', INTERVAL "
           f"({history['per_user']} + 30 - FLOOR((id - {start}) / {users})) DAY)")
    raw = mysql("SET time_zone = '+00:00';\n"
        "SELECT 'database_ok', DATABASE() = 'rougether_scale';\n"
        "SELECT 'all_todos', COUNT(*) FROM todos;\n"
        f"SELECT 'historical_todos', COUNT(*) FROM todos WHERE id BETWEEN {start} AND {end};\n"
        "SELECT 'invalid_history', COUNT(*) FROM todos "
        f"WHERE id BETWEEN {start} AND {end} AND NOT ("
        f"user_id = {user_start} + MOD(id - {start}, {users}) "
        f"AND due_date <=> {due} AND status = 'COMPLETED' "
        f"AND completed_at <=> TIMESTAMP(DATE_ADD({due}, INTERVAL 1 DAY), '12:00:00') "
        f"AND created_at <=> TIMESTAMP({due}, '12:00:00') "
        f"AND updated_at <=> TIMESTAMP(DATE_ADD({due}, INTERVAL 1 DAY), '12:00:00') "
        "AND reward_amount = 0 AND growth_reward_amount = 0 "
        "AND deleted_at IS NULL);\n"
        "SELECT 'historical_reward_ledgers', COUNT(*) FROM wallet_histories "
        f"WHERE source_type = 'TODO' AND source_id BETWEEN {start} AND {end};\n")
    actual = {key: int(value) for key, value in (line.split('\t') for line in raw.strip().splitlines())}
    expected = {"database_ok": 1, "all_todos": int(fixture["todoCount"]) + history["count"],
                "historical_todos": history["count"], "invalid_history": 0,
                "historical_reward_ledgers": 0}
    return {"passed": actual == expected, "actual": actual, "expected": expected}


def index_warmup_sql(index_rows):
    # SHOW INDEX에서 얻은 이름만 사용하며, 실제 부하 쿼리에는 hint를 넣지 않는다.
    names = sorted({line.split('\t')[2] for line in index_rows.strip().splitlines()})
    return "\n".join(f"SELECT COUNT(*) FROM todos FORCE INDEX (`{name.replace('`', '``')}`);"
                     for name in names) + "\n"


def prepare(directory, mysql, fixture, per_user, index_mode, manifest, warm_indexes=False):
    history = history_metadata(fixture, per_user)
    manifest["history"] = history
    manifest["todo_indexes"] = index_mode
    manifest["todo_index_cache_warm"] = warm_indexes
    manifest["fixture_profile"] = "minimal active fixture plus deterministic overdue completed history"
    seed = directory / "history-seed.sql"
    seed.write_text(history_sql(fixture, history))
    manifest["history_sql_sha256"] = hashlib.sha256(seed.read_bytes()).hexdigest()
    print(f"history: {history['count']} rows; indexes={index_mode}", flush=True)
    started = time.monotonic()
    (directory / "history-seed-result.txt").write_text(mysql(seed))
    manifest["history_seed_seconds"] = time.monotonic() - started
    if index_mode not in ("baseline", "candidate"):
        raise ValueError("unknown index mode")
    sql = database_guard_sql("rougether_scale") + "\n"
    if index_mode == "candidate":
        sql += INDEX_SQL
    sql += "ANALYZE TABLE todos;\n"
    (directory / "index-setup.sql").write_text(sql)
    started = time.monotonic()
    (directory / "index-setup-result.txt").write_text(mysql(sql))
    manifest["index_setup_seconds"] = time.monotonic() - started
    index_rows = mysql("SHOW INDEX FROM todos;")
    (directory / "todo-indexes.tsv").write_text(index_rows)
    if warm_indexes:
        # 인덱스 생성 뒤 남는 cold page의 영향을 별도 조건으로 확인한다.
        warm_sql = directory / "index-warmup.sql"
        warm_sql.write_text(index_warmup_sql(index_rows))
        started = time.monotonic()
        counts = mysql(warm_sql)
        (directory / "index-warmup-result.txt").write_text(counts)
        expected_count = int(fixture["todoCount"]) + history["count"]
        if any(int(value) != expected_count for value in counts.strip().splitlines()):
            raise RuntimeError("index warmup row count mismatch")
        manifest["index_warmup_seconds"] = time.monotonic() - started
    snapshot = history_snapshot(mysql, fixture, history)
    save(directory / "history-before.json", snapshot)
    if not snapshot["passed"]:
        raise RuntimeError("historical fixture precondition failed")
    user = int(fixture["userStartId"])
    day = date.fromisoformat(fixture["date"])
    lower, upper = day - timedelta(days=1), day
    queries = {
        "today": f"SELECT t.* FROM todos t WHERE user_id={user} AND deleted_at IS NULL "
                 f"AND (NULL IS NULL OR category_id=NULL) AND (NULL IS NULL OR status=NULL) "
                 f"AND ('{day}' IS NULL OR due_date='{day}') ORDER BY due_date,id",
        "daily_reward": f"SELECT COALESCE(SUM(reward_amount),0) FROM todos WHERE user_id={user} "
                        f"AND status='COMPLETED' AND completed_at >= '{lower} 15:00:00' "
                        f"AND completed_at < '{upper} 15:00:00'"}
    plans = {name: {"sql": query, "explain_analyze": mysql(
        "SET time_zone = '+00:00'; EXPLAIN ANALYZE " + query + ";")}
        for name, query in queries.items()}
    save(directory / "index-plans.json", plans)


def executed_prefix(fixture, summary, manifest):
    """실행된 연속 iteration만 검증한다. offered-load 판정은 절대 변경하지 않는다."""
    errors = []
    if summary.get("profile") != "mixed":
        return None, ["executed prefix audit only supports mixed"]
    counts = {}
    for name in ("iterations", "sent_requests", "business_success", "completion_success",
                 "rejected_requests", "expected_conflicts"):
        value = audit.metric_values(summary, name).get("count")
        if not isinstance(value, (int, float)) or value < 0 or int(value) != value:
            errors.append(f"missing/invalid {name}")
        else:
            counts[name] = int(value)
    if errors:
        return None, errors
    iterations = counts["iterations"]
    if iterations <= 0:
        errors.append("no executed iterations")
    if not (iterations == counts["sent_requests"] == counts["business_success"]):
        errors.append("not every executed iteration has one business-successful HTTP request")
    if counts["rejected_requests"] or counts["expected_conflicts"]:
        errors.append("rejection/conflict in unique completion workload")
    cycles, tail = divmod(iterations, 5)
    expected_endpoints = {"me": cycles * 2 + int(tail >= 1) + int(tail >= 3),
                          "today": cycles * 2 + int(tail >= 2) + int(tail >= 4),
                          "complete": cycles}
    for name, count in expected_endpoints.items():
        if audit.metric_count(summary, f"business_success{{endpoint:{name}}}") != count:
            errors.append(f"client endpoint count mismatch: {name}")
    if manifest.get("server_arrivals") != expected_endpoints:
        errors.append("server completed counts do not match executed request distribution")
    if counts["completion_success"] != cycles or cycles > int(fixture["todoCount"]):
        errors.append("completion prefix size mismatch")
    if (manifest.get("execution_error") or not manifest.get("seed_date")
            or manifest.get("seed_date") != manifest.get("finished_date")):
        errors.append("execution error or date boundary")
    return (None if errors else cycles), errors


def finish(directory, mysql, manifest):
    fixture = json.loads((directory / "fixtures.json").read_text())
    summary = json.loads((directory / "summary.json").read_text())
    history = history_snapshot(mysql, fixture, manifest["history"])
    save(directory / "history-after.json", history)
    prefix, errors = executed_prefix(fixture, summary, manifest)
    report = {"scope": "executed_requests_not_offered_load", "passed": False,
              "expected_unique_completions": prefix, "errors": errors,
              "history_unchanged": history["passed"],
              "capacity_verdict_unchanged": True,
              "excluded_dropped_iterations": audit.metric_count(summary, "dropped_iterations") or 0}
    if prefix is not None:
        path = directory / "executed-audit-snapshot.tsv"
        path.write_text(mysql(audit.snapshot_sql(fixture, prefix)))
        snapshot = audit.parse_snapshot(path)
        expected = audit.expected_from_prefix(fixture, prefix)
        errors.extend(audit.audit_snapshot(fixture, expected, snapshot))
        report["completion_audit"], report["reward_audit"] = audit.audit_sections(fixture, prefix, expected, snapshot)
    if not history["passed"]:
        errors.append("historical todos changed or fixture count mismatch")
    report["passed"] = prefix is not None and not errors
    save(directory / "executed-db-audit.json", report)
    manifest["executed_audit_passed"] = report["passed"]
