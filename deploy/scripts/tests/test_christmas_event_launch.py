"""운영 DB 없이 상태 전이, 드리프트 가드와 트랜잭션 종료를 검증함."""
import copy
import importlib.util
import io
import json
import os
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "christmas-event-launch.py"
SPEC = importlib.util.spec_from_file_location("christmas_launch", SCRIPT)
launch = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = launch
SPEC.loader.exec_module(launch)


def fixture(staged=True, active=False):
    flag = int(active)
    theme = {"id": 501, "code": launch.THEME_CODE, "name": "크리스마스",
             "cover_image_key": launch.COVER_KEY if staged else None, "is_active": flag}
    items, event_pool = [], []
    for index, (key, (category, placement, surface, rarity)) in enumerate(launch.ITEMS.items()):
        item_id = 2000 + index
        items.append({"id": item_id, "theme_id": 501, "asset_key": key, "category_code": category,
                      "placement_type": placement, "surface_slot_type": surface, "character_slot_type": None,
                      "purchase_currency_type": None if staged else "DIAMOND", "price_amount": None if staged else 10,
                      "is_active": flag})
        event_pool.append({"id": 3000 + index, "gacha_id": 902, "item_id": item_id,
                           "reward_type": "ITEM", "rarity": rarity, "weight": 1, "is_active": 1,
                           "character_id": None, "currency_type": None, "reward_amount": None, "asset_key": key})
    gachas = [{"id": 902, "code": launch.EVENT_CODE, "name": "크리스마스", "cost_currency_type": "COIN",
               "cost_amount": 25, "draw_count": 1, "starts_at": launch.START_UTC, "ends_at": launch.END_UTC,
               "is_active": flag, "theme_id": 501}]
    regular = []
    for category_index, code in enumerate(launch.CATEGORY_CODES):
        gachas.append({"id": 700 + category_index, "code": code, "name": code, "cost_currency_type": "COIN",
                       "cost_amount": 25, "draw_count": 1, "starts_at": None, "ends_at": None,
                       "is_active": 1, "theme_id": None})
        tiers = ["일반"] * 15 if code != "furniture_gacha" else ["일반"] * 79 + ["희귀"] * 36 + ["전설"] * 14
        for index, rarity in enumerate(tiers):
            item_id = 10000 + category_index * 1000 + index
            surface = code.removesuffix("_gacha") if code != "furniture_gacha" else None
            regular.append({"item_id": item_id, "reward_type": "ITEM", "entry_active": 1, "item_active": 1,
                            "theme_active": 1, "machine_code": code, "asset_key": f"fixture/{code}/{index}.png",
                            "rarity": rarity, "placement_type": "surface_slot" if surface else "positioned",
                            "surface_slot_type": surface, "character_slot_type": None,
                            "category_code": surface or "furniture", "item_theme_code": "ordinary_theme"})
    attendance = [dict(launch.ATTENDANCE_FIELDS, id=777, is_active=flag, reward_character_id=99, character_reward_day=3, reward_character_code="fox", reward_character_reserved=1)] if staged else []
    return {"history": [{"version": "100", "checksum": 123, "success": 1},
                        {"version": "101", "checksum": 456, "success": 1},
                        {"version": "102", "checksum": 789, "success": 1}],
            "columns": set(launch.REQUIRED_COLUMNS), "expected_checksums": {},
            "theme": [theme], "items": items, "gachas": gachas, "event_pool": event_pool,
            "regular_pool": regular, "christmas_leaks": [], "attendance": attendance,
            "attendance_reward_item_ids": [],
            "clock": datetime(2026, 11, 9, 3)}


class Cursor:
    def __init__(self, connection):
        self.connection = connection
        self.rowcount = 0

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, sql, params=()):
        self.connection.statements.append((sql, params))
        if sql.startswith(("INSERT ", "UPDATE ")):
            self.connection.write_calls += 1
            if self.connection.fail_write == self.connection.write_calls:
                raise RuntimeError("mock database failure")
            self.rowcount = self.connection.rowcounts.pop(0)


class Connection:
    def __init__(self, rowcounts=(), fail_write=None):
        self.rowcounts = list(rowcounts)
        self.fail_write = fail_write
        self.statements = []
        self.write_calls = 0
        self.commits = 0
        self.rollbacks = 0

    def cursor(self):
        return Cursor(self)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.state = fixture()
        self.digest_patch = patch.object(launch, "BASELINE_DIGEST", launch.digest_regular(self.state["regular_pool"]))
        self.digest_patch.start()
        self.addCleanup(self.digest_patch.stop)


class SafetyTests(FixtureTests):
    def test_fox_reward_drift_is_refused(self):
        for field, wrong in (("reward_character_id", None), ("character_reward_day", 2),
                             ("reward_character_code", "cat"), ("reward_character_reserved", 0)):
            state = copy.deepcopy(self.state)
            state["attendance"][0][field] = wrong
            with self.subTest(field=field), self.assertRaises(launch.GuardError):
                launch.plan_launch(state, "check")

    def test_load_state_includes_character_schema_and_reward_projection(self):
        responses = iter([self.state["theme"], self.state["items"], self.state["gachas"],
                          self.state["event_pool"], [], self.state["regular_pool"],
                          self.state["attendance"], [], [{"now_utc": self.state["clock"]}]])
        def read(_connection, sql, params=()):
            if "flyway_schema_history" in sql:
                return self.state["history"]
            if "information_schema.columns" in sql:
                tables = set(re.findall(r"'([a-z_]+)'", sql))
                return [{"table_name": table, "column_name": column}
                        for table, column in launch.REQUIRED_COLUMNS if table in tables]
            if "FROM attendance_events WHERE code=" in sql:
                self.assertIn("character_reward_day", sql)
                self.assertIn("AS reward_character_code", sql)
                self.assertIn("AS reward_character_reserved", sql)
            return next(responses)
        with patch.object(launch, "fetch", side_effect=read):
            state = launch.load_state(None, False, {})
        self.assertEqual(launch.plan_launch(state, "check")[0]["state"], "STAGED")

    def test_default_mode_is_read_only(self):
        args = launch.parser().parse_args(["--db-host", "db.example", "--ca-file", "/tmp/ca.pem"])
        self.assertEqual(args.mode, "check")

    def test_cli_modes_are_mutually_exclusive(self):
        with self.assertRaises(launch.GuardError):
            launch.parser().parse_args(["--db-host", "db.example", "--ca-file", "/tmp/ca", "--stage", "--activate"])

    def test_check_plans_no_writes(self):
        summary, operations = launch.plan_launch(self.state, "check")
        self.assertEqual(summary["state"], "STAGED")
        self.assertEqual(operations, [])
        self.assertEqual(summary["specialRewardType"], "NONE")

    def test_schema_99_or_missing_101_refused(self):
        for history in ([{"version": "99", "checksum": 0, "success": 1}], self.state["history"][:1]):
            with self.subTest(history=history):
                self.state["history"] = history
                with self.assertRaises(launch.GuardError):
                    launch.plan_launch(self.state, "check")

    def test_failed_migration_refused(self):
        self.state["history"][0]["success"] = 0
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "check")

    def test_checksum_guard_match_and_mismatch(self):
        self.state["expected_checksums"] = {"100": 123, "101": 456}
        launch.plan_launch(self.state, "check")
        self.state["expected_checksums"]["101"] = 999
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "check")

    def test_missing_v100_column_refused(self):
        self.state["columns"].remove(("attendance_events", "mode"))
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "stage")

    def test_higher_schema_allowed_when_required_versions_and_guards_match(self):
        self.state["history"].append({"version": "102", "checksum": 789, "success": 1})
        self.assertEqual(launch.plan_launch(self.state, "check")[0]["state"], "STAGED")

    def test_missing_or_extra_target_asset_refused(self):
        for delta in ("missing", "extra"):
            state = copy.deepcopy(self.state)
            if delta == "missing":
                state["items"].pop()
            else:
                state["items"].append(dict(state["items"][0], id=9999, asset_key="unexpected.png"))
            with self.subTest(delta=delta), self.assertRaises(launch.GuardError):
                launch.plan_launch(state, "stage")

    def test_item_placement_drift_refused(self):
        self.state["items"][0]["surface_slot_type"] = "floor"
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "stage")

    def test_price_drift_refused(self):
        for currency, amount in [("DIAMOND", 5), ("COIN", 10), (None, 10)]:
            state = copy.deepcopy(self.state)
            state["items"][0].update(purchase_currency_type=currency, price_amount=amount)
            with self.subTest(currency=currency, amount=amount), self.assertRaises(launch.GuardError):
                launch.plan_launch(state, "stage")

    def test_cover_drift_refused(self):
        self.state["theme"][0]["cover_image_key"] = "themes/unexpected-cover.png"
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "stage")

    def test_stage_only_allowed_master_mutations(self):
        state = fixture(staged=False)
        event = next(g for g in state["gachas"] if g["code"] == launch.EVENT_CODE)
        event["theme_id"] = None
        summary, operations = launch.plan_launch(state, "stage")
        self.assertEqual(summary["state"], "UNSTAGED")
        self.assertEqual(len(operations), 13)
        self.assertTrue(any("SET theme_id=" in op.sql for op in operations))
        insert = next(op for op in operations if op.sql.startswith("INSERT"))
        self.assertIn("FALSE,UTC_TIMESTAMP", insert.sql)
        self.assertIn("CUMULATIVE", insert.params)
        self.assertNotIn("is_active=TRUE", " ".join(op.sql for op in operations))
        for op in operations:
            table = op.sql.split()[1] if op.sql.startswith("UPDATE") else op.sql.split()[2]
            self.assertIn(table, launch.ALLOWED_WRITE_TABLES)

    def test_stage_existing_staged_is_noop(self):
        self.assertEqual(launch.plan_launch(self.state, "stage")[1], [])

    def test_active_exact_config_is_noop_even_after_deadline(self):
        state = fixture(active=True)
        state["clock"] = datetime(2027, 1, 1)
        for mode in ("stage", "activate"):
            summary, operations = launch.plan_launch(state, mode)
            self.assertEqual(summary["state"], "ACTIVE")
            self.assertEqual(operations, [])

    def test_partial_activation_never_repaired(self):
        self.state["items"][0]["is_active"] = 1
        for mode in ("check", "stage", "activate"):
            with self.subTest(mode=mode), self.assertRaises(launch.GuardError):
                launch.plan_launch(self.state, mode)

    def test_active_unstaged_configuration_refused(self):
        state = fixture(active=True)
        state["theme"][0]["cover_image_key"] = None
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(state, "stage")

    def test_invalid_active_value_refused(self):
        self.state["items"][0]["is_active"] = 2
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "stage")

    def test_activation_requires_completed_stage(self):
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(fixture(staged=False), "activate")

    def test_event_gacha_master_drift_refused(self):
        for name, value in [("cost_amount", 30), ("draw_count", 6), ("ends_at", datetime(2026, 12, 25, 14, 59, 59))]:
            state = copy.deepcopy(self.state)
            next(g for g in state["gachas"] if g["code"] == launch.EVENT_CODE)[name] = value
            with self.subTest(name=name), self.assertRaises(launch.GuardError):
                launch.plan_launch(state, "activate")

    def test_event_pool_missing_extra_inactive_or_wrong_tier_refused(self):
        for mutation in ("missing", "extra", "inactive", "tier"):
            state = copy.deepcopy(self.state)
            if mutation == "missing":
                state["event_pool"].pop()
            elif mutation == "extra":
                state["event_pool"].append({"asset_key": None})
            elif mutation == "inactive":
                state["event_pool"][0]["is_active"] = 0
            else:
                state["event_pool"][0]["rarity"] = "일반"
            with self.subTest(mutation=mutation), self.assertRaises(launch.GuardError):
                launch.plan_launch(state, "activate")

    def test_regular_same_counts_but_changed_asset_set_refused(self):
        self.state["regular_pool"][0]["asset_key"] = "unapproved/extra-wallpaper.png"
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "activate")

    def test_regular_attendance_exclusive_item_changes_effective_pool_and_is_refused(self):
        self.state["attendance_reward_item_ids"] = [self.state["regular_pool"][0]["item_id"]]
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "activate")

    def test_unrelated_attendance_exclusive_item_does_not_change_pool(self):
        self.state["attendance_reward_item_ids"] = [999999]
        self.assertEqual(launch.plan_launch(self.state, "check")[0]["regularDistinctItemCount"], 159)

    def test_category_codes_match_server_derived_enum_instead_of_a_database_column(self):
        repo = SCRIPT.parents[2]
        enum = (repo / "domain/src/main/java/com/triples/rougether/domain/gacha/entity/GachaCategory.java").read_text()
        entity = (repo / "domain/src/main/java/com/triples/rougether/domain/gacha/entity/Gacha.java").read_text()
        codes = set(re.findall(r'[A-Z_]+[(]"([a-z_]+_gacha)"', enum))
        self.assertEqual(codes, set(launch.CATEGORY_CODES))
        self.assertIn("return GachaCategory.fromCode(code);", entity)

    def test_regular_identical_duplicate_is_deduplicated(self):
        self.state["regular_pool"].append(dict(self.state["regular_pool"][0]))
        self.assertEqual(launch.plan_launch(self.state, "check")[0]["regularDistinctItemCount"], 159)

    def test_regular_conflicting_duplicate_refused(self):
        self.state["regular_pool"].append(dict(self.state["regular_pool"][0], rarity="희귀"))
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "check")

    def test_adding_active_catalog_only_wallpaper_to_pool_refused(self):
        self.state["regular_pool"].append(dict(self.state["regular_pool"][0], item_id=999999, asset_key="extra/catalog-wallpaper.png"))
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "check")

    def test_any_christmas_leak_including_inactive_pool_row_refused(self):
        self.state["christmas_leaks"] = [{"id": 999, "is_active": 0}]
        with self.assertRaises(launch.GuardError):
            launch.plan_launch(self.state, "activate")

    def test_attendance_exact_reward_and_mode_fields(self):
        for name, value in [("mode", "STREAK"), ("target_days", 7), ("milestone_every", 6),
                            ("milestone_coin_amount", 61), ("bonus_day", 5), ("bonus_coin_amount", 60),
                            ("reward_item_id", 1), ("generation_credit_amount", 1), ("title", "다른 제목")]:
            state = copy.deepcopy(self.state)
            state["attendance"][0][name] = value
            with self.subTest(name=name), self.assertRaises(launch.GuardError):
                launch.plan_launch(state, "activate")

    def test_first_and_last_valid_activation_dates(self):
        for now in (launch.START_UTC, datetime(2026, 11, 26, 14, 59, 59)):
            launch.activation_window(now)
        for now in (datetime(2026, 11, 8, 14, 59, 59), datetime(2026, 11, 26, 15), launch.END_UTC):
            with self.subTest(now=now), self.assertRaises(launch.GuardError):
                launch.activation_window(now)

    def test_activation_operations_only_activate_derived_target_ids(self):
        _, operations = launch.plan_launch(self.state, "activate")
        self.assertEqual(len(operations), 4)
        self.assertEqual([op.expected_rows for op in operations], [1, 10, 1, 1])
        self.assertEqual(operations[0].params, (501,))
        self.assertEqual(operations[2].params, (902,))
        self.assertEqual(operations[3].params, (777,))


class TransactionTests(FixtureTests):
    def test_check_rolls_back_and_never_commits_or_writes(self):
        connection = Connection()
        with patch.object(launch, "load_state", return_value=self.state) as load:
            result = launch.run(connection)
        self.assertEqual(result["result"], "READ_ONLY")
        self.assertEqual(connection.write_calls, 0)
        self.assertEqual(connection.commits, 0)
        self.assertEqual(connection.rollbacks, 1)
        self.assertIn("READ ONLY", connection.statements[0][0])
        load.assert_called_once_with(connection, False, {})

    def test_stage_snapshots_before_atomic_commit(self):
        before = fixture(staged=False)
        _, ops = launch.plan_launch(before, "stage")
        connection = Connection([op.expected_rows for op in ops])
        with tempfile.TemporaryDirectory() as folder:
            backup = Path(folder) / "before.json"
            with patch.object(launch, "load_state", side_effect=[before, fixture()]):
                result = launch.run(connection, "stage", backup)
            payload = json.loads(backup.read_text())
            self.assertEqual(payload["snapshotKind"], "configuration-master-only-before-write")
            self.assertEqual(payload["attendance"], [])
            self.assertEqual(os.stat(backup).st_mode & 0o777, 0o600)
        self.assertEqual(result["result"], "COMMITTED")
        self.assertEqual(connection.commits, 1)
        self.assertEqual(connection.rollbacks, 0)

    def test_activation_is_four_mutations_then_single_commit(self):
        _, ops = launch.plan_launch(self.state, "activate")
        connection = Connection([op.expected_rows for op in ops])
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(launch, "load_state", side_effect=[self.state, fixture(active=True)]), patch.object(launch, "fetch", return_value=[{"now_utc": self.state["clock"]}]):
                result = launch.run(connection, "activate", Path(folder) / "before.json")
        self.assertEqual(result["state"], "ACTIVE")
        self.assertEqual(connection.write_calls, 4)
        self.assertEqual(connection.commits, 1)

    def test_write_failure_rolls_back_without_commit(self):
        _, ops = launch.plan_launch(self.state, "activate")
        connection = Connection([op.expected_rows for op in ops], fail_write=2)
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(launch, "load_state", return_value=self.state), patch.object(launch, "fetch", return_value=[{"now_utc": self.state["clock"]}]):
                with self.assertRaises(RuntimeError):
                    launch.run(connection, "activate", Path(folder) / "before.json")
        self.assertEqual(connection.commits, 0)
        self.assertEqual(connection.rollbacks, 1)

    def test_rowcount_mismatch_rolls_back(self):
        connection = Connection([0])
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(launch, "load_state", return_value=self.state), patch.object(launch, "fetch", return_value=[{"now_utc": self.state["clock"]}]):
                with self.assertRaises(launch.GuardError):
                    launch.run(connection, "activate", Path(folder) / "before.json")
        self.assertEqual(connection.commits, 0)
        self.assertEqual(connection.rollbacks, 1)

    def test_failed_post_write_verification_rolls_back(self):
        _, ops = launch.plan_launch(self.state, "activate")
        connection = Connection([op.expected_rows for op in ops])
        wrong = fixture(active=True)
        wrong["attendance"][0]["daily_coin_amount"] = 999
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(launch, "load_state", side_effect=[self.state, wrong]), patch.object(launch, "fetch", return_value=[{"now_utc": self.state["clock"]}]):
                with self.assertRaises(launch.GuardError):
                    launch.run(connection, "activate", Path(folder) / "before.json")
        self.assertEqual(connection.commits, 0)
        self.assertEqual(connection.rollbacks, 1)

    def test_existing_snapshot_refused_before_first_mutation(self):
        connection = Connection()
        with tempfile.TemporaryDirectory() as folder:
            backup = Path(folder) / "before.json"
            backup.write_text("keep")
            with patch.object(launch, "load_state", return_value=self.state):
                with self.assertRaises(launch.GuardError):
                    launch.run(connection, "activate", backup)
            self.assertEqual(backup.read_text(), "keep")
        self.assertEqual(connection.write_calls, 0)
        self.assertEqual(connection.commits, 0)

    def test_drift_refuses_before_snapshot_or_write(self):
        self.state["items"][0]["price_amount"] = 9
        connection = Connection()
        with tempfile.TemporaryDirectory() as folder:
            backup = Path(folder) / "before.json"
            with patch.object(launch, "load_state", return_value=self.state):
                with self.assertRaises(launch.GuardError):
                    launch.run(connection, "activate", backup)
            self.assertFalse(backup.exists())
        self.assertEqual(connection.write_calls, 0)

    def test_write_cli_requires_snapshot_before_connect(self):
        with patch.object(launch, "connect") as connect, redirect_stderr(io.StringIO()):
            code = launch.main(["--stage", "--db-host", "db.example", "--ca-file", "/tmp/ca"])
        self.assertEqual(code, 2)
        connect.assert_not_called()

    def test_external_exception_does_not_print_secret(self):
        output = io.StringIO()
        with patch.object(launch, "connect", side_effect=RuntimeError("password=DO_NOT_PRINT")), redirect_stderr(output):
            code = launch.main(["--db-host", "db.example", "--ca-file", "/tmp/ca"])
        self.assertEqual(code, 1)
        self.assertNotIn("DO_NOT_PRINT", output.getvalue())
        self.assertNotIn("password=", output.getvalue())


if __name__ == "__main__":
    unittest.main()
