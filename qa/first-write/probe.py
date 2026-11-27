"""Run inside the disposable qa500 Compose network. stdout contains no credentials."""
import base64
import collections
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import sys
import threading
import time
import urllib.error
import urllib.request

WIDTH = 24
ROUNDS = 3
N = WIDTH * ROUNDS
CAP_GUESTS = 12
FIRST = 500000


def seed():
    for i in range(N + 1 + CAP_GUESTS):
        owner = FIRST + i * 2
        guest = owner + 1
        code = f"I{i:07}"
        print(f"INSERT INTO users (id,nickname,email,created_at,updated_at) VALUES "
              f"({owner},'owner{i}','o{i}@example.invalid',NOW(),NOW()),"
              f"({guest},'guest{i}','g{i}@example.invalid',NOW(),NOW());")
        print(f"INSERT INTO user_wallets (user_id,currency_type,balance,created_at,updated_at) "
              f"VALUES ({owner},'COIN',100,NOW(),NOW()),({guest},'COIN',100,NOW(),NOW());")
        print(f"INSERT INTO user_invite_codes (user_id,code,created_at) VALUES ({owner},'{code}',NOW());")
        print(f"INSERT INTO house (id,owner_user_id,name,current_member_count,max_members,level,"
              f"growth_points,is_public,invite_code,invite_expires_at,created_at,updated_at) VALUES "
              f"({owner},{owner},'qa{i}',1,10,1,0,0,'H{i:07}',NOW()+INTERVAL 7 DAY,NOW(),NOW());")
        print(f"INSERT INTO house_members (house_id,user_id,role,status,joined_at) "
              f"VALUES ({owner},{owner},'OWNER','ACTIVE',NOW());")


def encode(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def request(route, i, barrier, inviter=None):
    owner = FIRST + i * 2
    if route == "invite":
        method, path, body, user = "POST", "/invites/redeem", {"code": f"I{i if inviter is None else inviter:07}"}, owner + 1
    elif route == "onboarding":
        method, path, body, user = "PUT", "/onboarding/house", {"choice": "PERSONAL"}, owner
    else:
        method, path, body, user = "GET", "/me/furniture-credits", None, owner
    now = int(time.time())
    claims = dict(sub=str(user), typ="access", role="NORMAL", iat=now, exp=now + 600)
    parts = encode(b'{"alg":"HS256","typ":"JWT"}') + "." + encode(json.dumps(claims).encode())
    jwt = parts + "." + encode(hmac.new(os.environ["QA500_JWT_SECRET"].encode(), parts.encode(), hashlib.sha256).digest())
    req = urllib.request.Request("http://api:8080/api/v1" + path,
                                 data=json.dumps(body).encode() if body else None, method=method,
                                 headers={"Authorization": "Bearer " + jwt, "Content-Type": "application/json"})
    barrier.wait(timeout=30)
    try:
        with urllib.request.urlopen(req, timeout=40) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as exc:
        return exc.code, json.load(exc)


def burst(route, ids, inviter=None):
    barrier = threading.Barrier(len(ids))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(ids)) as pool:
        return list(pool.map(lambda i: request(route, i, barrier, inviter), ids))


def run():
    result = {}
    passed = True
    timings = {}
    for route in ("invite", "onboarding", "credit"):
        responses = []
        started = time.monotonic()
        for start in range(0, N, WIDTH):
            responses.extend(burst(route, range(start, start + WIDTH)))
        elapsed = time.monotonic() - started
        timings[route] = {"elapsed": elapsed, "users": [FIRST + 2 * i for i, (status, _) in enumerate(responses) if status == 200]}
        statuses = collections.Counter(status for status, _ in responses)
        passed &= statuses == {200: N}
        result[route] = dict(statuses)
        for i, (status, body) in enumerate(responses):
            if status != 200:
                continue
            if route == "invite":
                passed &= body == {"rewardCoin": 50, "coinBalance": 150, "inviterRewarded": True}
            elif route == "onboarding":
                passed &= body["completed"] and body["result"] == "PERSONAL" and body["houseId"] == FIRST + 2 * i
            else:
                passed &= bool(body["accountToken"]) and body["available"] == body["reserved"] == 0

        same = burst(route, [N] * WIDTH)
        statuses = collections.Counter(status for status, _ in same)
        result[route + "_same_user"] = dict(statuses)
        if route == "invite":
            passed &= statuses == {200: 1, 409: WIDTH - 1} and all(
                (s == 200 and r["rewardCoin"] == 50 and r["coinBalance"] == 150)
                or (s == 409 and r.get("code") == "INVITE_ALREADY_REDEEMED") for s, r in same)
        else:
            passed &= statuses == {200: WIDTH} and all(r == same[0][1] for _, r in same)
            if statuses == {200: WIDTH}:
                first = same[0][1]
                if route == "credit":
                    passed &= bool(first["accountToken"]) and first["available"] == first["reserved"] == 0
                else:
                    passed &= first["completed"] and first["houseId"] == FIRST + 2 * N and first["result"] == "PERSONAL"
    cap = burst("invite", range(N + 1, N + 1 + CAP_GUESTS), inviter=N + 1)
    awarded = sum(status == 200 and body["inviterRewarded"] for status, body in cap)
    passed &= all(status == 200 and body["coinBalance"] == 150 for status, body in cap) and awarded == 10
    result["same_inviter"] = {"accepted": sum(s == 200 for s, _ in cap), "inviter_rewards": awarded}
    Path("/tmp/qa500-timing.json").write_text(json.dumps(timings))
    print(json.dumps(result, indent=2))
    return passed


def report_tps():
    timings = json.loads(Path("/tmp/qa500-timing.json").read_text())
    committed = collections.defaultdict(list)
    for line in sys.stdin:
        route, user_id = line.split()
        committed[route].append(int(user_id))
    for route, timing in timings.items():
        expected = sorted(timing["users"])
        if sorted(committed[route]) != expected:
            raise ValueError(f"{route}: HTTP successes and committed users differ")
        elapsed = timing["elapsed"]
        print(f"{route} committed={len(expected)}/{N} elapsed_s={elapsed:.6f} TPS={len(expected)/elapsed:.2f}")


if __name__ == "__main__":
    if sys.argv[1:] == ["seed"]:
        seed()
    elif sys.argv[1:] == ["tps"]:
        report_tps()
    else:
        sys.exit(0 if run() else 1)
