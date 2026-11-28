"""Extract only validated client and feature settings from the verified source close operation."""
import argparse
import json
import re
import sys
from pathlib import Path

BOOL = set("MARKET_ENGINE_ENABLED CHRISTMAS_EVENT_ENABLED CHRISTMAS_COIN_BONUS_ENABLED BILLING_ENABLED BILLING_REQUIRE_CREDITS BILLING_WORKER_ENABLED FURNITURE_GENERATION_ENABLED FURNITURE_UPLOAD_ENABLED FURNITURE_LAMBDA_DISPATCH_ENABLED CHAT_CLEANUP_ENABLED FEED_CLEANUP_ENABLED LOGIN_GIFT_RETRY_ENABLED ACTIVITY_BATCH_ENABLED".split())
PUBLIC = {
    "GOOGLE_ALLOWED_CLIENT_IDS": r"[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com(?:,[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com)*",
    "APPLE_ALLOWED_CLIENT_IDS": r"[A-Za-z0-9.-]+(?:,[A-Za-z0-9.-]+)*",
    "KAKAO_APP_ID": r"[0-9]+",
    "INVITE_SHARE_BASE_URL": r"https://[a-zA-Z0-9.-]+(?:/[a-zA-Z0-9._/-]*)?",
    "INVITE_APP_SCHEME": r"[a-z][a-z0-9+.\-]*",
    "INVITE_ANDROID_PACKAGE": r"[a-zA-Z][\w]*(?:\.[a-zA-Z][\w]*)+",
    "INVITE_ANDROID_CERT_FINGERPRINTS": r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}(?:,(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})*",
    "INVITE_APPSTORE_ID": r"[0-9]+",
    "INVITE_APPLE_APP_ID": r"[A-Z0-9]{10}\.[a-zA-Z0-9.-]+",
}


def validated(env):
    settings = {}
    for key in sorted(BOOL | PUBLIC.keys()):
        value = env.get(key)
        if value is None or value == "":
            continue
        if key in BOOL:
            value = value.lower()
            pattern = "true|false"
        else:
            value = ",".join(v.strip() for v in value.split(","))
            pattern = PUBLIC[key]
        if len(value) > 4000 or not re.fullmatch(pattern, value):
            raise ValueError("Invalid source setting " + key)
        settings[key] = value
    return settings


def export(output, sha, run_id):
    lines = [line.removeprefix("ROUGETHER_SOURCE_FEATURES=") for line in output.splitlines()
             if line.startswith("ROUGETHER_SOURCE_FEATURES=")]
    if len(lines) != 1 or not re.fullmatch("[0-9a-f]{40}", sha) or not run_id.isdigit():
        raise ValueError("Missing source settings or release identity")
    original = json.loads(lines[0])
    settings = validated(original)
    if original != settings:
        raise ValueError("Unexpected or unnormalized source settings")
    return {"sourceAccount":"776158585524","region":"ap-northeast-2","release":sha,
            "run":run_id,"userApiSettings":settings}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("sha")
    parser.add_argument("run_id")
    parser.add_argument("destination", type=Path)
    args=parser.parse_args()
    args.destination.write_text(json.dumps(export(sys.stdin.read(), args.sha, args.run_id),sort_keys=True))
