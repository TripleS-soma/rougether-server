#!/usr/bin/env python3
"""Runner-friendly wrapper that writes seed.sql, fixtures.json, and optional tokens.json."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--users", type=positive_int, default=1000)
    parser.add_argument("--todos", type=positive_int, help="deprecated alias for --todo-count")
    parser.add_argument("--todo-count", type=positive_int, help="total seeded todos")
    parser.add_argument("--todos-per-user", type=positive_int, default=10)
    parser.add_argument("--date", required=True, help="KST fixture date, YYYY-MM-DD")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jwt-secret", help="test-only JWT_SECRET used by user-api")
    parser.add_argument("--user-start", type=positive_int, default=900_000_000)
    parser.add_argument("--todo-start", type=positive_int, default=910_000_000)
    parser.add_argument("--wallet-start", type=positive_int, default=920_000_000)
    return parser.parse_args(argv)


def run(args: list[str]) -> None:
    subprocess.run([sys.executable, *args], check=True)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.todos and args.todo_count:
        raise SystemExit("use only one of --todos or --todo-count")
    todo_count = args.todo_count or args.todos or (args.users * args.todos_per_user)
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = args.output / "fixtures.json"
    run(
        [
            str(HERE / "seed_scale_db.py"),
            "--users",
            str(args.users),
            "--todos-per-user",
            str(args.todos_per_user),
            "--todo-count",
            str(todo_count),
            "--date",
            args.date,
            "--user-start",
            str(args.user_start),
            "--todo-start",
            str(args.todo_start),
            "--wallet-start",
            str(args.wallet_start),
            "--sql-out",
            str(args.output / "seed.sql"),
            "--fixture-json",
            str(fixture),
        ]
    )
    if args.jwt_secret:
        token_path = args.output / "tokens.json"
        run(
            [
                str(HERE / "mint_test_jwts.py"),
                "--fixture-json",
                str(fixture),
                "--jwt-secret",
                args.jwt_secret,
                "--out",
                str(token_path),
            ]
        )
        token_data = token_path.read_text(encoding="utf-8")
        fixture_data = json.loads(fixture.read_text(encoding="utf-8"))
        fixture_data["users"] = json.loads(token_data)["users"]
        fixture.write_text(json.dumps(fixture_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
