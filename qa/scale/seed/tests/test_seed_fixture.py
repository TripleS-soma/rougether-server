import json
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
SEED = ROOT / "qa" / "scale" / "seed" / "seed_scale_db.py"
MINT = ROOT / "qa" / "scale" / "seed" / "mint_test_jwts.py"
spec = importlib.util.spec_from_file_location("seed_scale_db", SEED)
seed_scale_db = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed_scale_db)


class CountingSink:
    def __init__(self):
        self.write_count = 0
        self.max_chunk = 0
        self.total = 0

    def write(self, text):
        self.write_count += 1
        self.max_chunk = max(self.max_chunk, len(text))
        self.total += len(text)


class SeedFixtureTest(unittest.TestCase):
    def test_seed_emits_compact_round_robin_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            sql_path = tmp_path / "seed.sql"
            fixture_path = tmp_path / "fixtures.json"

            subprocess.run(
                [
                    sys.executable,
                    str(SEED),
                    "--users",
                    "3",
                    "--todos-per-user",
                    "2",
                    "--date",
                    "2026-09-09",
                    "--sql-out",
                    str(sql_path),
                    "--fixture-json",
                    str(fixture_path),
                ],
                check=True,
            )

            fixture = json.loads(fixture_path.read_text())
            sql = sql_path.read_text()
            self.assertEqual(fixture["usersCount"], 3)
            self.assertEqual(fixture["todoCount"], 6)
            self.assertEqual(fixture["mapping"]["strategy"], "round_robin_ordinal")
            self.assertEqual(fixture["seedProfile"]["users"]["bot"], False)
            self.assertIn("Refusing to seed outside rougether scale database", sql)
            self.assertIn("(910000003, 900000000", sql)

    def test_cli_rejects_unsafe_database_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(
                [
                    sys.executable,
                    str(SEED),
                    "--users",
                    "1",
                    "--todo-count",
                    "1",
                    "--date",
                    "2026-09-09",
                    "--database-name",
                    "rougether_scale_tmp",
                    "--fixture-json",
                    str(Path(tmp) / "fixtures.json"),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--database-name must be rougether_scale", result.stderr)

    def test_streamed_sql_writes_bounded_batches(self):
        args = seed_scale_db.parse_args(
            [
                "--users",
                "1000",
                "--todo-count",
                "5000",
                "--date",
                "2026-09-09",
                "--batch-size",
                "100",
                "--fixture-json",
                "/tmp/unused-fixtures.json",
            ]
        )
        sink = CountingSink()

        seed_scale_db.write_sql(args, sink)

        self.assertGreater(sink.write_count, 100)
        self.assertGreater(sink.total, sink.max_chunk)
        self.assertLess(sink.max_chunk, 50_000)


    def test_jwt_minter_matches_token_service_claims(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            fixture_path = tmp_path / "fixtures.json"
            out_path = tmp_path / "tokens.json"
            fixture_path.write_text(
                json.dumps(
                    {
                        "seedId": "rougether-scale-v1",
                        "date": "2026-09-09",
                        "userStartId": 900000000,
                        "usersCount": 2,
                        "jwt": {"algorithm": "HS256"},
                    }
                )
            )

            subprocess.run(
                [
                    sys.executable,
                    str(MINT),
                    "--fixture-json",
                    str(fixture_path),
                    "--jwt-secret",
                    "test-only-secret-hs256-needs-at-least-32-bytes-long-padding",
                    "--out",
                    str(out_path),
                ],
                check=True,
            )

            tokens = json.loads(out_path.read_text())
            self.assertEqual([user["id"] for user in tokens["users"]], [900000000, 900000001])
            self.assertTrue(all(user["token"].count(".") == 2 for user in tokens["users"]))


if __name__ == "__main__":
    unittest.main()
