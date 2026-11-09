#!/usr/bin/env python3
"""크리스마스 구성 마스터만 점검/스테이징/활성화함. 사용자 행은 조회하지 않음."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import ssl
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

EVENT_CODE = "christmas_2026"
THEME_CODE = "snowy_christmas_202611"
COVER_KEY = "themes/snowy-christmas-202611-cover.png"
START_DATE, END_DATE = date(2026, 11, 9), date(2026, 12, 25)
START_UTC = datetime(2026, 11, 8, 15)
END_UTC = datetime(2026, 12, 25, 15)  # KST 12/26 00:00 미포함
CATEGORY_CODES = ("wallpaper_gacha", "floor_gacha", "furniture_gacha")
RARITIES = ("일반", "희귀", "전설")
BASELINE_COUNT = 159
BASELINE_TIERS = {"일반": 109, "희귀": 36, "전설": 14}
BASELINE_CATEGORY_COUNTS = {"wallpaper_gacha": 15, "floor_gacha": 15, "furniture_gacha": 129}
# 11/9 12:00 KST 읽기 점검의 (카테고리 코드, asset key, 등급) 집합임. 숫자 item ID는 고정하지 않음.
BASELINE_DIGEST = "75db0faae01d364af4e115a9e0f44e1dd691caca628678709d634db19cdde56a"
PREFIX = "items/snowy-christmas-202611/"
ITEMS = {
    PREFIX + "furniture/christmas-tree-animated-v1.webp": ("furniture", "positioned", None, "전설"),
    PREFIX + "furniture/stocking-fireplace-animated-v1.webp": ("furniture", "positioned", None, "전설"),
    PREFIX + "furniture/plaid-sofa-v1.png": ("furniture", "positioned", None, "희귀"),
    PREFIX + "furniture/gift-stack-v1.png": ("furniture", "positioned", None, "일반"),
    PREFIX + "furniture/cocoa-table-animated-v1.webp": ("furniture", "positioned", None, "희귀"),
    PREFIX + "furniture/snowy-window-animated-v1.webp": ("furniture", "positioned", None, "일반"),
    PREFIX + "furniture/christmas-wreath-v1.png": ("furniture", "positioned", None, "희귀"),
    PREFIX + "furniture/winter-rug-v1.png": ("furniture", "positioned", None, "일반"),
    PREFIX + "wallpaper/snowflake-wallpaper-panel-free-v2.png": ("wallpaper", "surface_slot", "wallpaper", "일반"),
    PREFIX + "floor/honey-winter-floor-v1.png": ("floor", "surface_slot", "floor", "일반"),
}
ATTENDANCE_FIELDS = {
    "code": EVENT_CODE, "title": "크리스마스", "starts_on": START_DATE, "ends_on": END_DATE,
    "target_days": 30, "mode": "CUMULATIVE", "daily_coin_amount": 30,
    "milestone_every": 5, "milestone_coin_amount": 60, "bonus_day": 30,
    "bonus_coin_amount": 30, "reward_item_id": None, "generation_credit_amount": 0,
}
REQUIRED_COLUMNS = {
    ("attendance_events", "mode"), ("attendance_events", "milestone_every"),
    ("attendance_events", "milestone_coin_amount"), ("attendance_check_ins", "progress_completed_at"),
    ("event_popup_dismissals", "event_code"), ("event_popup_dismissals", "dismissed_at"),
}
ALLOWED_WRITE_TABLES = frozenset({"themes", "items", "gacha", "attendance_events"})


class GuardError(Exception):
    """운영 값 드리프트나 실행 전제 위반 시 중단함."""


def require(condition, reason):
    if not condition:
        raise GuardError(reason)


def exact(row, fields, label):
    for name, expected in fields.items():
        require(name in row and row[name] == expected, label + "." + name + " drift: manual review required")


def one(rows, label, optional=False):
    require(len(rows) == 1 or (optional and not rows), label + " missing or duplicated")
    return rows[0] if rows else None


def digest_regular(rows):
    identity = sorted((r["machine_code"], r["asset_key"], r["rarity"]) for r in rows)
    return hashlib.sha256(json.dumps(identity, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def validate_schema(history, columns, expected_checksums):
    require(history and not any(not r["success"] for r in history), "failed or empty Flyway history")
    versions = {str(r["version"]): r for r in history if r["version"] is not None}
    require({"100", "101"}.issubset(versions), "Flyway V100 and V101 must both be applied")
    require(max(int(v.split(".")[0]) for v in versions) >= 101, "schema version must be at least 101")
    require(REQUIRED_COLUMNS.issubset(columns), "required V100 columns missing")
    for version, checksum in expected_checksums.items():
        require(version in versions and versions[version]["checksum"] == checksum, "Flyway checksum mismatch for V" + version)


@dataclass(frozen=True)
class Operation:
    sql: str
    params: tuple
    expected_rows: int


def classify(row):
    if row.get("character_slot_type") is not None or row.get("category_code") in ("character_accessory", "background"):
        return None
    if row["placement_type"] == "surface_slot" and row["surface_slot_type"] in ("wallpaper", "floor"):
        return row["surface_slot_type"] + "_gacha"
    if row["placement_type"] == "positioned" and row["surface_slot_type"] is None:
        return "furniture_gacha"
    return None


def regular_baseline(state):
    by_id = {}
    exclusive_rewards = set(state["attendance_reward_item_ids"])
    for row in state["regular_pool"]:
        if not (row["entry_active"] and row["item_active"] and row["theme_active"]):
            continue
        if row["item_id"] in exclusive_rewards:
            continue
        require(row["reward_type"] == "ITEM" and classify(row) == row["machine_code"], "regular pool category drift")
        require(row["rarity"] in RARITIES, "regular pool rarity drift")
        require(row["asset_key"] not in ITEMS and row["item_theme_code"] != THEME_CODE, "Christmas leaked into regular pool")
        previous = by_id.get(row["item_id"])
        if previous:
            require((previous["asset_key"], previous["rarity"], previous["machine_code"]) ==
                    (row["asset_key"], row["rarity"], row["machine_code"]), "regular item rarity/category conflict")
        by_id[row["item_id"]] = row
    rows = list(by_id.values())
    require(len(rows) == BASELINE_COUNT, "regular pool distinct count drift")
    require(dict(Counter(r["rarity"] for r in rows)) == BASELINE_TIERS, "regular pool tier count drift")
    require(dict(Counter(r["machine_code"] for r in rows)) == BASELINE_CATEGORY_COUNTS, "regular category count drift")
    require(digest_regular(rows) == BASELINE_DIGEST, "regular pool asset/rarity set drift")
    return rows


def validate_master(state):
    validate_schema(state["history"], state["columns"], state.get("expected_checksums", {}))
    theme = one(state["theme"], "Christmas theme")
    exact(theme, {"code": THEME_CODE, "name": "크리스마스"}, "theme")
    require(theme["cover_image_key"] in (None, COVER_KEY), "theme cover drift")
    items = state["items"]
    require(len(items) == len(ITEMS) and {r["asset_key"] for r in items} == set(ITEMS), "Christmas must contain exactly the approved ten asset keys")
    require(len({r["id"] for r in items}) == len(ITEMS), "duplicate Christmas item")
    for row in items:
        category, placement, surface, _ = ITEMS[row["asset_key"]]
        exact(row, {"theme_id": theme["id"], "category_code": category, "placement_type": placement,
                    "surface_slot_type": surface, "character_slot_type": None}, "item")
        require((row["purchase_currency_type"], row["price_amount"]) in ((None, None), ("DIAMOND", 10)), "item direct-purchase configuration drift")
    gachas = {code: one([r for r in state["gachas"] if r["code"] == code], code)
              for code in (*CATEGORY_CODES, EVENT_CODE)}
    for code in CATEGORY_CODES:
        exact(gachas[code], {"cost_currency_type": "COIN", "cost_amount": 25, "draw_count": 1,
                            "starts_at": None, "ends_at": None, "is_active": 1, "theme_id": None}, code)
    gacha = gachas[EVENT_CODE]
    exact(gacha, {"name": "크리스마스", "cost_currency_type": "COIN", "cost_amount": 25,
                  "draw_count": 1, "starts_at": START_UTC, "ends_at": END_UTC}, "event gacha")
    require(gacha["theme_id"] in (None, theme["id"]), "event gacha theme drift")
    pool = state["event_pool"]
    require(len(pool) == 10 and {r["asset_key"] for r in pool} == set(ITEMS), "V101 event pool must contain exactly ten approved entries")
    require(len({r["item_id"] for r in pool}) == 10, "duplicate event pool item")
    item_ids = {r["asset_key"]: r["id"] for r in items}
    for row in pool:
        exact(row, {"gacha_id": gacha["id"], "item_id": item_ids[row["asset_key"]], "reward_type": "ITEM",
                    "rarity": ITEMS[row["asset_key"]][3], "weight": 1, "is_active": 1,
                    "character_id": None, "currency_type": None, "reward_amount": None}, "event pool")
    require(not state["christmas_leaks"], "Christmas entry exists outside the dedicated event pool")
    regular_baseline(state)
    attendance = one(state["attendance"], "Christmas attendance", optional=True)
    if attendance:
        exact(attendance, ATTENDANCE_FIELDS, "attendance")
    for row in [theme, gacha, *items, *([attendance] if attendance else [])]:
        require(row["is_active"] in (0, 1), "invalid activation flag")
    active = [bool(theme["is_active"]), bool(gacha["is_active"])] + [bool(r["is_active"]) for r in items]
    if attendance:
        active.append(bool(attendance["is_active"]))
    require(not any(active) or (all(active) and attendance is not None), "partially active Christmas configuration: manual review required")
    staged = (theme["cover_image_key"] == COVER_KEY and gacha["theme_id"] == theme["id"]
              and attendance is not None and all(r["purchase_currency_type"] is None and r["price_amount"] is None for r in items))
    require(not any(active) or staged, "active event has unstaged master fields")
    return theme, items, gacha, attendance, staged, all(active) and attendance is not None


def activation_window(now):
    require(START_UTC <= now < END_UTC, "activation is outside the event interval")
    today = (now + timedelta(hours=9)).date()
    require((END_DATE - today).days + 1 >= 30, "fewer than thirty attendance dates remain")


def plan_launch(state, mode):
    require(mode in ("check", "stage", "activate"), "unsupported mode")
    theme, items, gacha, attendance, staged, active = validate_master(state)
    now = state["clock"]
    summary = {"eventCode": EVENT_CODE, "mode": mode, "checkedAtUtc": now,
               "checkedAtKst": now + timedelta(hours=9), "state": "ACTIVE" if active else "STAGED" if staged else "UNSTAGED",
               "itemCount": len(items), "eventPoolCount": len(state["event_pool"]), "regularDistinctItemCount": BASELINE_COUNT,
               "regularTierCounts": BASELINE_TIERS, "christmasRegularLeakCount": 0,
               "attendanceExists": attendance is not None, "specialRewardType": "NONE",
               "directPurchaseDisabled": all(r["purchase_currency_type"] is None and r["price_amount"] is None for r in items),
               "coverLinked": theme["cover_image_key"] == COVER_KEY, "writesPlanned": 0}
    if mode == "check" or active:
        return summary, []
    operations = []
    if mode == "stage":
        if theme["cover_image_key"] is None:
            operations.append(Operation("UPDATE themes SET cover_image_key=%s WHERE id=%s AND cover_image_key IS NULL", (COVER_KEY, theme["id"]), 1))
        if gacha["theme_id"] is None:
            operations.append(Operation("UPDATE gacha SET theme_id=%s WHERE id=%s AND theme_id IS NULL", (theme["id"], gacha["id"]), 1))
        for row in items:
            if row["purchase_currency_type"] is not None:
                operations.append(Operation("UPDATE items SET purchase_currency_type=NULL,price_amount=NULL WHERE id=%s AND purchase_currency_type='DIAMOND' AND price_amount=10", (row["id"],), 1))
        if attendance is None:
            fields = list(ATTENDANCE_FIELDS)
            sql = "INSERT INTO attendance_events (" + ",".join(fields) + ",is_active,created_at) VALUES (" + ",".join(["%s"] * len(fields)) + ",FALSE,UTC_TIMESTAMP(6))"
            operations.append(Operation(sql, tuple(ATTENDANCE_FIELDS[k] for k in fields), 1))
    else:
        require(staged, "run --stage first; activation never repairs missing configuration")
        activation_window(now)
        operations.extend([
            Operation("UPDATE themes SET is_active=TRUE WHERE id=%s AND is_active=FALSE", (theme["id"],), 1),
            Operation("UPDATE items SET is_active=TRUE WHERE theme_id=%s AND asset_key IN (" + ",".join(["%s"] * 10) + ") AND is_active=FALSE", (theme["id"], *sorted(ITEMS)), 10),
            Operation("UPDATE gacha SET is_active=TRUE WHERE id=%s AND is_active=FALSE", (gacha["id"],), 1),
            Operation("UPDATE attendance_events SET is_active=TRUE WHERE id=%s AND is_active=FALSE", (attendance["id"],), 1),
        ])
    summary["writesPlanned"] = len(operations)
    return summary, operations


def fetch(connection, sql, params=()):
    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.fetchall()


def load_state(connection, lock, expected_checksums):
    suffix = " FOR UPDATE" if lock else ""
    history = fetch(connection, "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
    columns = fetch(connection, "SELECT TABLE_NAME AS table_name,COLUMN_NAME AS column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('attendance_events','attendance_check_ins','event_popup_dismissals')")
    column_set = {(r["table_name"], r["column_name"]) for r in columns}
    validate_schema(history, column_set, expected_checksums)
    theme = fetch(connection, "SELECT id,code,name,cover_image_key,is_active FROM themes WHERE code=%s" + suffix, (THEME_CODE,))
    target = one(theme, "Christmas theme")
    items = fetch(connection, "SELECT id,theme_id,asset_key,category_code,placement_type,surface_slot_type,character_slot_type,purchase_currency_type,price_amount,is_active FROM items WHERE theme_id=%s ORDER BY id" + suffix, (target["id"],))
    codes = (*CATEGORY_CODES, EVENT_CODE)
    gachas = fetch(connection, "SELECT id,code,name,cost_currency_type,cost_amount,draw_count,starts_at,ends_at,is_active,theme_id FROM gacha WHERE code IN (%s,%s,%s,%s) ORDER BY code" + suffix, codes)
    event = one([r for r in gachas if r["code"] == EVENT_CODE], "V101 Christmas gacha")
    event_pool = fetch(connection, "SELECT p.id,p.gacha_id,p.item_id,p.reward_type,p.character_id,p.currency_type,p.reward_amount,p.rarity,p.weight,p.is_active,i.asset_key FROM gacha_pool_entries p LEFT JOIN items i ON i.id=p.item_id WHERE p.gacha_id=%s ORDER BY p.id" + suffix, (event["id"],))
    leaks = fetch(connection, "SELECT p.id FROM gacha_pool_entries p JOIN gacha g ON g.id=p.gacha_id JOIN items i ON i.id=p.item_id WHERE i.theme_id=%s AND g.code<>%s ORDER BY p.id" + suffix, (target["id"], EVENT_CODE))
    regular = fetch(connection, "SELECT p.id,p.item_id,p.reward_type,p.rarity,p.is_active AS entry_active,g.code AS machine_code,i.asset_key,i.category_code,i.placement_type,i.surface_slot_type,i.character_slot_type,i.is_active AS item_active,t.is_active AS theme_active,t.code AS item_theme_code FROM gacha_pool_entries p JOIN gacha g ON g.id=p.gacha_id JOIN items i ON i.id=p.item_id JOIN themes t ON t.id=i.theme_id WHERE g.code IN (%s,%s,%s) ORDER BY g.code,p.item_id,p.id" + suffix, CATEGORY_CODES)
    attendance = fetch(connection, "SELECT id,code,title,starts_on,ends_on,target_days,mode,daily_coin_amount,milestone_every,milestone_coin_amount,bonus_day,bonus_coin_amount,reward_item_id,generation_credit_amount,is_active FROM attendance_events WHERE code=%s" + suffix, (EVENT_CODE,))
    attendance_rewards = fetch(connection, "SELECT reward_item_id FROM attendance_events WHERE reward_item_id IS NOT NULL ORDER BY reward_item_id" + suffix)
    clock = fetch(connection, "SELECT UTC_TIMESTAMP(6) AS now_utc")[0]["now_utc"]
    return {"history": history, "columns": column_set, "expected_checksums": expected_checksums, "theme": theme,
            "items": items, "gachas": gachas, "event_pool": event_pool, "christmas_leaks": leaks,
            "regular_pool": regular, "attendance": attendance,
            "attendance_reward_item_ids": [r["reward_item_id"] for r in attendance_rewards], "clock": clock}


def write_snapshot(path, state, mode):
    require(path is not None, "a new --snapshot file is required for writes")
    payload = dict(state)
    payload["columns"] = sorted(state["columns"])
    payload["mode"] = mode
    payload["snapshotKind"] = "configuration-master-only-before-write"
    try:
        fd = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except OSError:
        raise GuardError("snapshot path must be a new writable regular file") from None
    with os.fdopen(fd, "w") as stream:
        json.dump(payload, stream, ensure_ascii=False, default=str, indent=2)
        stream.flush()
        os.fsync(stream.fileno())


def run(connection, mode="check", snapshot_path=None, expected_checksums=None):
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET TRANSACTION READ ONLY" if mode == "check" else "SET TRANSACTION READ WRITE")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
        state = load_state(connection, mode != "check", expected_checksums or {})
        summary, operations = plan_launch(state, mode)
        if not operations:
            connection.rollback()
            summary["result"] = "READ_ONLY" if mode == "check" else "NOOP"
            return summary
        write_snapshot(snapshot_path, state, mode)
        if mode == "activate":
            activation_window(fetch(connection, "SELECT UTC_TIMESTAMP(6) AS now_utc")[0]["now_utc"])
        with connection.cursor() as cursor:
            for operation in operations:
                cursor.execute(operation.sql, operation.params)
                require(cursor.rowcount == operation.expected_rows, "write row count drift; transaction rolled back")
        after = load_state(connection, True, expected_checksums or {})
        final, _ = plan_launch(after, "check")
        require(final["state"] == ("ACTIVE" if mode == "activate" else "STAGED"), "post-write master verification failed")
        connection.commit()
        final.update(mode=mode, result="COMMITTED", writesPlanned=len(operations), snapshotWritten=True)
        return final
    except Exception:
        connection.rollback()
        raise


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        raise GuardError("invalid CLI arguments; see --help")


def checksum_argument(value):
    try:
        version, checksum = value.split("=", 1)
        require(version.isdigit(), "checksum version must be numeric")
        number = int(checksum)
        require(-(2 ** 31) <= number < 2 ** 31, "checksum must be a signed 32-bit integer")
        return version, number
    except (ValueError, GuardError):
        raise argparse.ArgumentTypeError("expected VERSION=SIGNED_CHECKSUM") from None


def parser():
    result = SafeParser(description=__doc__)
    modes = result.add_mutually_exclusive_group()
    modes.add_argument("--check", action="store_const", const="check", dest="mode", help="기본값: 읽기 전용 점검")
    modes.add_argument("--stage", action="store_const", const="stage", dest="mode", help="비활성 구성만 스테이징")
    modes.add_argument("--activate", action="store_const", const="activate", dest="mode", help="기간/집합 검증 후 원자적 활성화")
    result.set_defaults(mode="check")
    result.add_argument("--db-host", required=True, help="TLS 검증용 실제 RDS 호스트명")
    result.add_argument("--db-name", default="rougether")
    result.add_argument("--db-user", default="rougether")
    result.add_argument("--connect-host", help="SSM 터널이면 127.0.0.1; TLS SNI는 db-host 유지")
    result.add_argument("--connect-port", type=int, default=3306)
    result.add_argument("--ca-file", type=Path, required=True)
    result.add_argument("--aws-profile")
    result.add_argument("--aws-region", default="ap-northeast-2")
    result.add_argument("--password-ssm-param", default="/rougether-dev/db/password")
    result.add_argument("--snapshot", type=Path, help="쓰기 전 마스터 백업의 새 파일 경로")
    result.add_argument("--expected-checksum", type=checksum_argument, action="append", default=[])
    return result


def connect(args):
    import boto3
    import pymysql
    context = ssl.create_default_context(cafile=str(args.ca_file))
    require(context.check_hostname and context.verify_mode == ssl.CERT_REQUIRED, "TLS verification must remain enabled")
    aws = boto3.Session(profile_name=args.aws_profile, region_name=args.aws_region)
    password = aws.client("ssm").get_parameter(Name=args.password_ssm_param, WithDecryption=True)["Parameter"]["Value"]
    connection = pymysql.connect(host=args.db_host, user=args.db_user, password=password, database=args.db_name,
                                 ssl=context, defer_connect=True, autocommit=False, connect_timeout=10,
                                 read_timeout=30, write_timeout=30, cursorclass=pymysql.cursors.DictCursor)
    try:
        connection.connect(sock=socket.create_connection((args.connect_host or args.db_host, args.connect_port), timeout=10))
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION time_zone='+00:00'")
        return connection
    except Exception:
        connection.close()
        raise


def main(argv=None):
    connection = None
    try:
        args = parser().parse_args(argv)
        require(1 <= args.connect_port <= 65535, "invalid connect port")
        require(args.mode == "check" or args.snapshot is not None, "--stage/--activate require --snapshot")
        expected = dict(args.expected_checksum)
        require(len(expected) == len(args.expected_checksum), "duplicate checksum version")
        connection = connect(args)
        summary = run(connection, args.mode, args.snapshot, expected)
        print(json.dumps(summary, ensure_ascii=False, default=str, indent=2))
        return 0
    except GuardError as error:
        print(json.dumps({"result": "REFUSED", "reason": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2
    except Exception as error:
        # SDK/DB 오류 원문에는 자격증명이 섞일 수 있어 타입만 출력함.
        print(json.dumps({"result": "FAILED", "errorType": type(error).__name__, "reason": "operation failed; connection/permissions or drift require review"}), file=sys.stderr)
        return 1
    finally:
        if connection is not None:
            try:
                connection.close()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
