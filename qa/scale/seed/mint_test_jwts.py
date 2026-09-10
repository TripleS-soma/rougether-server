#!/usr/bin/env python3
"""Mint test-only JWTs matching user-api TokenService access-token claims."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import sys
from pathlib import Path


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def sign_hs256(message: str, secret: str) -> str:
    digest = hmac.new(secret.encode("utf-8"), message.encode("ascii"), hashlib.sha256).digest()
    return b64url(digest)


def mint(user_id: int, secret: str, issued_at: int, ttl_seconds: int) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": str(user_id),
        "typ": "access",
        "role": "NORMAL",
        "iat": issued_at,
        "exp": issued_at + ttl_seconds,
    }
    signing_input = ".".join(
        (
            b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
            b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
        )
    )
    return f"{signing_input}.{sign_hs256(signing_input, secret)}"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-json", type=Path, required=True)
    parser.add_argument("--jwt-secret", required=True)
    parser.add_argument("--ttl-seconds", type=int, default=6 * 60 * 60)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--tokens", choices=("all", "none"), default="all")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if len(args.jwt_secret.encode("utf-8")) < 32:
        raise SystemExit("--jwt-secret must be at least 32 bytes for HS256")
    fixture = json.loads(args.fixture_json.read_text(encoding="utf-8"))
    now = int(dt.datetime.now(dt.timezone.utc).timestamp())
    user_start = int(fixture["userStartId"])
    users_count = int(fixture["usersCount"])
    output = {
        "schemaVersion": 1,
        "seedId": fixture["seedId"],
        "date": fixture["date"],
        "userStartId": user_start,
        "usersCount": users_count,
        "jwt": fixture["jwt"],
        "users": [],
    }
    if args.tokens == "all":
        output["users"] = [
            {"id": user_start + idx, "token": mint(user_start + idx, args.jwt_secret, now, args.ttl_seconds)}
            for idx in range(users_count)
        ]
    text = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
