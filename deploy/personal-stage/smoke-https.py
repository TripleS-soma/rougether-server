"""Test a synthetic account via the exact HTTPS routes used by the phone."""
import argparse
import base64
import json
import os
import secrets
import ssl
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener

p = argparse.ArgumentParser()
p.add_argument("origin")
p.add_argument("--ca")
args = p.parse_args()
assert urlsplit(args.origin).scheme == "https"
ctx = ssl.create_default_context(cafile=args.ca)
opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ctx))

def send(path, method="GET", data=None, *, code=None, token=None, expected=200, origin=None):
    headers = {"Content-Type": "application/json"}
    if origin is not None or method in ("POST", "PUT"):
        headers["Origin"] = origin if origin is not None else args.origin
    if code:
        headers["Authorization"] = "Basic " + base64.b64encode(("rehearsal:" + code).encode()).decode()
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(args.origin + path, method=method, headers=headers,
                  data=json.dumps(data).encode() if data is not None else None)
    try:
        response = opener.open(req, timeout=12)
    except HTTPError as exc:
        response = exc
    with response:
        assert response.status == expected, (path, response.status, expected)
        data = response.read()
        if response.status == 200 and data and "json" in response.headers.get("Content-Type", ""):
            return json.loads(data)

assert send("/api/v1/health")["status"] == "ok"
send("/")
send("/mobile.js")
send("/api/v1/auth/dev-login", "POST", {}, expected=404)
send("/rehearsal/session", "POST", {}, code="wrong", expected=401)
send("/admin/health", expected=404)
send("/actuator/health", expected=404)
send("/api/v1/me", expected=401)
send("/api/v1/auth/google", "POST", {"idToken":"invalid"}, expected=401)
code = os.environ["STAGE_CODE"]
send("/rehearsal/session", "POST", {}, code=code, origin="https://other.invalid", expected=403)
def create():
    login = send("/rehearsal/session", "POST", {"userId": 1}, code=code)
    assert login["isNewUser"]
    return login
a, b = create(), create()
assert a['userId'] != b['userId']
token = a["accessToken"]
send("/api/v1/me", token=token)
name = "qa" + secrets.token_hex(4)
bio = "서울 전환 테스트 " + secrets.token_hex(3)
send("/api/v1/me", "PUT", {"nickname": name, "bio": bio}, token=token)
member = send("/api/v1/me", token=token)
assert member["bio"] == bio and member["nickname"] == name
print("PASS TLS, health, mobile page, fixture login, authenticated read/write/read, route isolation")
