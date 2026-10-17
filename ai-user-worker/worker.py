"""예약 기동 후 제한된 작업과 기억 반영을 순차 처리하고 종료함."""
from __future__ import annotations

import json
import logging
import os
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "ai-service"))
from rougether_ai.ai_user_policy import ACTION_HELP, SYSTEM, code_plan, parse_plan, planning_actions

log = logging.getLogger("ai-user-worker")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Redirect not allowed")


@dataclass(frozen=True)
class Settings:
    control_url: str
    control_token: str = field(repr=False)
    ai_service_url: str
    ai_service_token: str = field(repr=False)
    owner: str = field(default_factory=lambda: uuid.uuid4().hex)
    max_jobs: int = 10
    max_seconds: float = 600
    memory_batch_limit: int = 10
    allow_private_http: bool = False

    def __post_init__(self):
        for base in (self.control_url, self.ai_service_url):
            parsed = urlparse(base)
            local = parsed.hostname in ("localhost", "127.0.0.1", "::1")
            if parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.hostname or parsed.path:
                raise ValueError("Invalid service origin")
            if parsed.scheme != "https" and not (parsed.scheme == "http" and (local or self.allow_private_http)):
                raise ValueError("Service URL requires HTTPS")
        for token in (self.control_token, self.ai_service_token):
            if len(token) < 32 or not all(33 <= ord(c) <= 126 for c in token):
                raise ValueError("Missing worker credentials")
        if not 1 <= self.max_jobs <= 30 or not 1 <= self.memory_batch_limit <= 10 or not 1 <= self.max_seconds <= 600:
            raise ValueError("Invalid burst budget")

    @classmethod
    def from_env(cls):
        return cls(os.environ["AI_USER_CONTROL_URL"].rstrip("/"), os.environ["AI_USER_CONTROL_TOKEN"],
                   os.environ["AI_USER_SERVICE_URL"].rstrip("/"), os.environ["AI_USER_SERVICE_TOKEN"],
                   max_jobs=int(os.getenv("AI_USER_MAX_JOBS", "10")),
                   max_seconds=float(os.getenv("AI_USER_MAX_SECONDS", "600")),
                   memory_batch_limit=int(os.getenv("AI_USER_MEMORY_BATCH_LIMIT", "10")),
                   allow_private_http=os.getenv("AI_USER_ALLOW_PRIVATE_HTTP", "false").lower()=="true")


class JsonHttp:
    def __init__(self):
        self.opener = build_opener(NoRedirect())

    def post(self, url, payload, headers, timeout=60):
        request = Request(url, json.dumps(payload, ensure_ascii=False).encode(),
                          {"Content-Type": "application/json", **headers}, method="POST")
        with self.opener.open(request, timeout=timeout) as response:
            raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise ValueError("Response too large")
            return json.loads(raw) if raw else {}


class Worker:
    def __init__(self, settings, http=None):
        self.settings, self.http = settings, http or JsonHttp()

    def control(self, path, body):
        return self.http.post(self.settings.control_url + "/internal/ai-users" + path, body,
                              {"Authorization": "Bearer " + self.settings.control_token}, timeout=30)

    def ai(self, path, body):
        return self.http.post(self.settings.ai_service_url + "/internal/v1/ai-users" + path, body,
                              {"Authorization": "Bearer " + self.settings.ai_service_token},
                              timeout=190 if path=="/episodes" else 60 if path=="/decide" else 30)

    def tick(self):
        claim = self.control("/claim", {"owner": self.settings.owner})
        if not claim:
            return False
        job = claim["job"]
        lease = {"owner": self.settings.owner, "token": job["fencingToken"]}
        try:
            state = self.control(f"/jobs/{job['id']}/snapshot", lease)
            if state.get("aiUserId") != job["userId"]:
                raise ValueError("Account mismatch")
            state["actions"] = planning_actions(state)
            plan = code_plan(state)
            if plan is None:
                self.sanitize_memories(job["userId"])
                source = state.get("source", {})
                original=source.get("comment") or source.get("message") or source.get("post") or {}
                query = json.dumps(original
                                   or {"personaKey": state.get("personaKey"), "date": state.get("date")}, ensure_ascii=False)[:2000]
                counterpart=original.get("author_id") or original.get("sender_user_id") or original.get("author",{}).get("userId")
                candidates = self.ai("/recall", {"aiUserId": job["userId"], "query": query,"counterpartUserId":counterpart})
                facts = self.control(f"/jobs/{job['id']}/memory-context", {**lease, "facts": candidates})
                accepted = {e for fact in facts for e in fact["episodes"]}
                rejected = {e for fact in candidates for e in fact["episodes"]} - accepted
                if rejected:
                    try:
                        self.ai("/forget", {"aiUserId": job["userId"], "episodes": sorted(rejected)[:32]})
                    except Exception:
                        log.warning("memory_purge_deferred")
                plan = self.ai("/decide", {"aiUserId": job["userId"], "state": state, "facts": facts})
                # 모델 결과가 제어 API에 도착하기 전에 허용 action과 actor override를 다시 확인함.
                plan = parse_plan({"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps(plan)}]}, state["actions"])
            body = {**lease, **plan}
            for attempt in range(3):
                try:
                    self.control(f"/jobs/{job['id']}/execute", body)
                    break
                except (OSError, HTTPError) as error:
                    if isinstance(error, HTTPError) and error.code < 500:
                        raise
                    if attempt == 2:
                        raise
                    time.sleep(attempt + 1)
            log.info("job=%s completed", job["id"])
        except Exception as error:
            log.warning("job=%s failure_type=%s", job["id"], type(error).__name__)
            retryable = isinstance(error, OSError) and (not isinstance(error, HTTPError) or error.code==429 or error.code>=500)
            try:
                self.control(f"/jobs/{job['id']}/{'defer' if retryable else 'fail'}", lease)
            except Exception:
                log.warning("job=%s settlement_unknown", job["id"])
        return True

    def sanitize_memories(self, user):
        # SDK가 기존 사실과 요약을 추출에 재사용하기 전에도 원문의 현재 접근권을 확인함.
        cursor = ""
        deadline = time.monotonic()+60
        while True:
            if time.monotonic()>=deadline:
                raise OSError("Memory verification budget exceeded")
            inventory = self.ai("/inventory", {"aiUserId": user, "cursor": cursor})
            invalid = self.control("/memory/check", {"aiUserId": user, "records": inventory["records"]})
            for offset in range(0, len(invalid), 32):
                self.ai("/forget", {"aiUserId": user, "episodes": invalid[offset:offset+32]})
            next_cursor = inventory["nextCursor"]
            if next_cursor is None:
                return
            if next_cursor <= cursor:
                raise ValueError("Invalid memory inventory cursor")
            cursor = next_cursor

    def sync_memories(self, deadline):
        pending = self.control("/memory/pending", {"limit": self.settings.memory_batch_limit})
        for episode in pending:
            if time.monotonic() >= deadline:
                break
            try:
                self.sanitize_memories(episode["aiUserId"])
                self.ai("/episodes", episode)
            except Exception as error:
                log.warning("memory_sync_failure_type=%s", type(error).__name__)
                self.control(f"/memory/{episode['jobId']}/ack", {"success": False,
                    "revoked": isinstance(error, HTTPError) and error.code == 409})
            else:
                self.control(f"/memory/{episode['jobId']}/ack", {"success": True, "revoked": False})

    def run(self):
        deadline = time.monotonic() + self.settings.max_seconds
        handled = 0
        while handled < self.settings.max_jobs and time.monotonic() < deadline:
            if not self.tick():
                break
            handled += 1
        if time.monotonic() < deadline:
            self.sync_memories(deadline)
        log.info("burst_finished jobs=%s", handled)
        return handled


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    Worker(Settings.from_env()).run()
