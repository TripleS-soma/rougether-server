#!/usr/bin/env python3
"""Runner-facing DB audit that can emit SQL or consume a saved TSV snapshot."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SUCCESS_STATUS = 201
CORE_SCALAR_LABELS = (
    "meta_database_ok",
    "todo_total",
    "owner_mismatch",
    "duplicate_history",
    "history_swaps",
)
PLAN_SCALAR_LABELS = (
    "completed_outside_expected_prefix",
    "missing_expected_prefix_completion",
)
SCALAR_LABELS = CORE_SCALAR_LABELS + PLAN_SCALAR_LABELS


def sql_quote(value: object) -> str:
    if value is None:
        return "NULL"
    text = str(value)
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


def summary_config(summary: dict) -> dict:
    return summary.get("config", summary)


def metric_values(summary: dict, name: str) -> dict:
    metric = summary.get("metrics", {}).get(name)
    if not isinstance(metric, dict):
        return {}
    values = metric.get("values")
    if isinstance(values, dict):
        return values
    return metric


def metric_count(summary: dict, name: str) -> int | None:
    count = metric_values(summary, name).get("count")
    if count is None:
        return None
    return int(count)


def planned_unique_completions(fixture: dict, summary: dict) -> tuple[int | None, list[str]]:
    config = summary_config(summary)
    scenario = summary.get("profile") or config.get("scenario")
    planned = metric_count(summary, "iterations")
    if planned is None:
        planned = metric_count(summary, "sent_requests")
    if planned is None:
        planned = config.get("plannedIterations", config.get("iterations"))
    dropped = metric_count(summary, "dropped_iterations")
    if dropped is None:
        dropped = config.get("droppedIterations", config.get("dropped_iterations", 0))
    completion_success = metric_count(summary, "completion_success")
    if completion_success is None:
        completion_success = config.get("completionSuccess", config.get("completion_success"))
    violations = []
    if scenario not in ("read", "write", "mixed", "contention"):
        violations.append(f"ambiguous scenario: {scenario}")
        return None, violations
    if planned is None:
        violations.append("summary missing iterations")
        return None, violations
    if dropped != 0:
        violations.append(f"run had dropped iterations: {dropped}")
        return None, violations
    if scenario == "read":
        expected = 0
    elif scenario == "write":
        expected = planned
    elif scenario == "mixed":
        expected = planned // 5
    else:
        audit = summary.get("audit", {})
        hot = audit.get("hotTodoCount", config.get("hotTodoCount"))
        if hot is None:
            violations.append("contention summary missing hotTodoCount")
            return None, violations
        expected = int(hot)
    if expected > int(fixture["todoCount"]):
        violations.append(f"expected completions {expected} exceed seeded todoCount {fixture['todoCount']}")
        return None, violations
    if completion_success is None:
        violations.append("summary missing completionSuccess")
        return None, violations
    if int(completion_success) != expected:
        violations.append(f"completionSuccess expected {expected}, actual {completion_success}")
        return None, violations
    return expected, violations


def expected_from_prefix(fixture: dict, expected_unique: int) -> dict:
    user_start = int(fixture["userStartId"])
    users_count = int(fixture["usersCount"])
    reward_amount = int(fixture["reward"]["todoCompleteAmount"])
    daily_cap = int(fixture["reward"]["dailyCap"])
    completions = {}
    for ordinal in range(expected_unique):
        user_id = user_start + (ordinal % users_count)
        completions[user_id] = completions.get(user_id, 0) + 1
    rewards = {user_id: min(daily_cap, reward_amount * count) for user_id, count in completions.items()}
    return {"completionsByUser": completions, "rewardsByUser": rewards}


def snapshot_sql(fixture: dict, expected_unique: int | None) -> str:
    user_start = int(fixture["userStartId"])
    users_count = int(fixture["usersCount"])
    user_end = user_start + users_count - 1
    todo_start = int(fixture["todoStartId"])
    todo_count = int(fixture["todoCount"])
    todo_end = todo_start + todo_count - 1
    database_name = fixture["databaseName"]
    if expected_unique is None:
        expected_end = todo_start - 1
    else:
        expected_end = todo_start + expected_unique - 1
    return "\n".join(
        [
            "SELECT 'meta_database_ok', DATABASE() = " + sql_quote(database_name) + ";",
            f"SELECT 'todo_total', COUNT(*) FROM todos WHERE id BETWEEN {todo_start} AND {todo_end};",
            "SELECT 'owner_mismatch', COUNT(*) FROM todos "
            f"WHERE id BETWEEN {todo_start} AND {todo_end} "
            f"AND user_id <> ({user_start} + MOD(id - {todo_start}, {users_count}));",
            "SELECT 'duplicate_history', COUNT(*) FROM ("
            "SELECT source_id FROM wallet_histories "
            f"WHERE source_type = 'TODO' AND source_id BETWEEN {todo_start} AND {todo_end} "
            "GROUP BY source_id HAVING COUNT(*) > 1) dup;",
            "SELECT 'history_swaps', COUNT(*) FROM wallet_histories wh JOIN todos t ON t.id = wh.source_id "
            f"WHERE wh.source_type = 'TODO' AND wh.source_id BETWEEN {todo_start} AND {todo_end} "
            "AND wh.user_id <> t.user_id;",
            "SELECT 'completed_outside_expected_prefix', COUNT(*) FROM todos "
            f"WHERE id BETWEEN {todo_start} AND {todo_end} AND status = 'COMPLETED' "
            f"AND NOT (id BETWEEN {todo_start} AND {expected_end});",
            "SELECT 'missing_expected_prefix_completion', COUNT(*) FROM todos "
            f"WHERE id BETWEEN {todo_start} AND {expected_end} AND status <> 'COMPLETED';",
            "SELECT 'completed', user_id, COUNT(*), COALESCE(SUM(reward_amount),0) FROM todos "
            f"WHERE id BETWEEN {todo_start} AND {todo_end} AND status = 'COMPLETED' GROUP BY user_id;",
            "SELECT 'history', wh.user_id, COUNT(*), COALESCE(SUM(wh.amount),0) FROM wallet_histories wh "
            f"WHERE wh.source_type = 'TODO' AND wh.source_id BETWEEN {todo_start} AND {todo_end} GROUP BY wh.user_id;",
            "SELECT 'wallet', user_id, COUNT(*), COALESCE(SUM(balance),0) FROM user_wallets "
            f"WHERE user_id BETWEEN {user_start} AND {user_end} AND currency_type = 'COIN' GROUP BY user_id;",
            "SELECT 'room', user_id, 1, growth_points FROM personal_rooms "
            f"WHERE user_id BETWEEN {user_start} AND {user_end};",
            "",
        ]
    )


def parse_snapshot(path: Path) -> dict:
    snapshot = {
        "scalars": {},
        "completed": {},
        "history": {},
        "wallet": {},
        "room": {},
    }
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        parts = line.split("\t")
        label = parts[0]
        if label in snapshot["scalars"]:
            raise SystemExit(f"duplicate scalar label at snapshot line {line_number}: {label}")
        if label in SCALAR_LABELS:
            snapshot["scalars"][label] = int(parts[1] or 0)
        elif label in ("completed", "history", "wallet", "room"):
            snapshot[label][int(parts[1])] = (int(parts[2] or 0), int(parts[3] or 0))
        else:
            raise SystemExit(f"unknown snapshot label at line {line_number}: {label}")
    return snapshot


def positive_history_count(expected_completed: int, expected_reward: int, reward_amount: int) -> int:
    if expected_reward <= 0:
        return 0
    return min(expected_completed, expected_reward // reward_amount)


def audit_snapshot(fixture: dict, expected: dict, snapshot: dict) -> list[str]:
    errors = []
    user_start = int(fixture["userStartId"])
    users_count = int(fixture["usersCount"])
    reward_amount = int(fixture["reward"]["todoCompleteAmount"])

    scalars = snapshot["scalars"]
    errors.extend(audit_core_scalars(fixture, snapshot, SCALAR_LABELS))
    if scalars.get("completed_outside_expected_prefix", 0):
        errors.append(f"completed todos outside expected prefix: {scalars['completed_outside_expected_prefix']}")
    if scalars.get("missing_expected_prefix_completion", 0):
        errors.append(f"missing expected prefix completions: {scalars['missing_expected_prefix_completion']}")

    for user_offset in range(users_count):
        user_id = user_start + user_offset
        expected_completed = expected["completionsByUser"].get(user_id, 0)
        expected_reward = expected["rewardsByUser"].get(user_id, 0)
        actual_completed, actual_todo_reward = snapshot["completed"].get(user_id, (0, 0))
        actual_history_count, actual_history_reward = snapshot["history"].get(user_id, (0, 0))
        actual_wallet_count, actual_wallet_balance = snapshot["wallet"].get(user_id, (0, 0))
        actual_room_count, actual_growth = snapshot["room"].get(user_id, (0, 0))
        if actual_completed != expected_completed:
            errors.append(f"user {user_id} completed todos expected {expected_completed}, actual {actual_completed}")
        if actual_todo_reward != expected_reward:
            errors.append(f"user {user_id} todo reward expected {expected_reward}, actual {actual_todo_reward}")
        expected_history_count = positive_history_count(expected_completed, expected_reward, reward_amount)
        if actual_history_count != expected_history_count:
            errors.append(f"user {user_id} wallet history count expected {expected_history_count}, actual {actual_history_count}")
        if actual_history_reward != expected_reward:
            errors.append(f"user {user_id} wallet history sum expected {expected_reward}, actual {actual_history_reward}")
        if actual_wallet_count != 1 or actual_wallet_balance != expected_reward:
            errors.append(f"user {user_id} COIN wallet expected {expected_reward}, actual rows={actual_wallet_count} sum={actual_wallet_balance}")
        if actual_room_count != 1 or actual_growth != expected_reward:
            errors.append(f"user {user_id} room growth expected {expected_reward}, actual rows={actual_room_count} points={actual_growth}")
        if len(errors) > 50:
            errors.append("stopping after 50 user-level mismatches")
            break
    return errors


def audit_independent_snapshot(fixture: dict, snapshot: dict) -> list[str]:
    return audit_core_scalars(fixture, snapshot, CORE_SCALAR_LABELS)


def audit_core_scalars(fixture: dict, snapshot: dict, required_labels: tuple[str, ...]) -> list[str]:
    errors = []
    todo_count = int(fixture["todoCount"])
    scalars = snapshot["scalars"]
    for label in required_labels:
        if label not in scalars:
            errors.append(f"snapshot missing {label}")
    if scalars.get("meta_database_ok") != 1:
        errors.append(f"connected database is not {fixture['databaseName']}")
    if scalars.get("todo_total") != todo_count:
        errors.append(f"seeded todo count mismatch: expected {todo_count}, actual {scalars.get('todo_total')}")
    if scalars.get("owner_mismatch", 0):
        errors.append(f"todo owner mapping mismatches: {scalars['owner_mismatch']}")
    if scalars.get("duplicate_history", 0):
        errors.append(f"duplicate wallet history source rows: {scalars['duplicate_history']}")
    if scalars.get("history_swaps", 0):
        errors.append(f"wallet history user/source owner swaps: {scalars['history_swaps']}")
    return errors


def audit_sections(fixture: dict, expected_unique: int | None, expected: dict | None, snapshot: dict) -> tuple[dict, dict]:
    scalars = snapshot["scalars"]
    actual_completed = sum(count for count, _reward in snapshot["completed"].values())
    actual_reward = sum(reward for _count, reward in snapshot["completed"].values())
    expected_reward = None if expected is None else sum(expected["rewardsByUser"].values())
    rewarded_users = None if expected is None else len(expected["rewardsByUser"])
    plan_dependent_completed_outside = None if expected is None else scalars.get("completed_outside_expected_prefix")
    plan_dependent_missing = None if expected is None else scalars.get("missing_expected_prefix_completion")
    completion_audit = {
        "expected_unique_completions": expected_unique,
        "actual_completed_todos": actual_completed,
        "completed_outside_expected_prefix": plan_dependent_completed_outside,
        "missing_expected_prefix_completion": plan_dependent_missing,
        "owner_mismatch": scalars.get("owner_mismatch"),
    }
    reward_audit = {
        "expected_reward_total": expected_reward,
        "actual_todo_reward_total": actual_reward,
        "duplicate_history_sources": scalars.get("duplicate_history"),
        "history_owner_swaps": scalars.get("history_swaps"),
        "rewarded_users": rewarded_users,
    }
    return completion_audit, reward_audit


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--emit-snapshot-sql", action="store_true")
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--out", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    summary = json.loads(args.summary.read_text(encoding="utf-8"))
    if summary.get("date") and summary["date"] != fixture["date"]:
        if not args.emit_snapshot_sql:
            raise SystemExit(f"run date {summary['date']} does not match fixture date {fixture['date']}")
    expected_unique, plan_violations = planned_unique_completions(fixture, summary)
    if args.emit_snapshot_sql:
        sys.stdout.write(snapshot_sql(fixture, expected_unique))
        return 0
    if not args.snapshot:
        raise SystemExit("--snapshot is required unless --emit-snapshot-sql is set")
    errors = list(plan_violations)
    snapshot = parse_snapshot(args.snapshot)
    if expected_unique is None:
        expected = None
        errors.extend(audit_independent_snapshot(fixture, snapshot))
    else:
        expected = expected_from_prefix(fixture, expected_unique)
        errors.extend(audit_snapshot(fixture, expected, snapshot))
    completion_audit, reward_audit = audit_sections(fixture, expected_unique, expected, snapshot)
    report = {
        "passed": not errors,
        "date": fixture["date"],
        "checked_tables": ["todos", "wallet_histories", "user_wallets", "personal_rooms"],
        "completion_audit": completion_audit,
        "reward_audit": reward_audit,
        "violations": errors,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
