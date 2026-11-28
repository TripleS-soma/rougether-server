"""Accept only the verified source release settings that the personal user API consumes."""
import base64
import json
import re
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


def render(encoded, destination):
    raw = base64.b64decode(encoded, validate=True)
    if len(raw)>16000:
        raise ValueError("Unexpected source settings length")
    data=json.loads(raw)
    if set(data)!={"sourceAccount","region","release","run","userApiSettings"} or data["sourceAccount"]!="776158585524" or data["region"]!="ap-northeast-2":
        raise ValueError("Unexpected source identity")
    if not re.fullmatch("[0-9a-f]{40}",data["release"]) or not re.fullmatch("[0-9]+",data["run"]):
        raise ValueError("Unexpected source release")
    flags=validated(data["userApiSettings"])
    if flags!=data["userApiSettings"]:
        raise ValueError("Unexpected source setting")
    if flags.get("BILLING_ENABLED")=="true":
        raise ValueError("Source store billing needs its verified marketplace configuration")
    path=Path(destination)
    text="".join(key+"="+value+"\n" for key,value in sorted(flags.items()))
    if path.exists():
        if path.read_text()!=text:raise ValueError("Source settings changed for this release")
    else:
        with path.open("x") as out:out.write(text)
    return {"sourceRelease":data["release"],"sourceSettingsRun":data["run"],"applied":len(flags)}


if __name__=="__main__":
    import sys
    print(json.dumps(render(sys.stdin.read().strip(),sys.argv[1])))
