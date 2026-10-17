"""실제 모델/HTTP/DB 평가용 실행기. 반드시 격리된 테스트 서버에서만 실행함."""
import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError

from worker import JsonHttp


class EvaluationHttp(JsonHttp):
    def __init__(self, report, max_calls):
        super().__init__()
        self.report = Path(report)
        self.ledger = Path(os.environ.get("AI_USER_EVAL_LEDGER", report + ".calls.jsonl"))
        self.max_calls = max_calls
        self.failed = False
        self.fatal = False

    def write(self, event):
        with self.report.open("a", encoding="utf-8") as output:
            output.write(json.dumps(event, ensure_ascii=False) + "\n")

    def post(self, url, payload, headers, timeout=60):
        model_call = not url.startswith(os.environ["AI_USER_CONTROL_URL"] + "/")
        if model_call:
            # 기존 기록도 세어 프로세스 재시작으로 호출 상한을 초기화하지 않음.
            previous = sum(json.loads(line).get("kind") == "model" for line in self.ledger.read_text().splitlines()) if self.ledger.exists() else 0
            if previous >= self.max_calls:
                raise RuntimeError("Live evaluation call budget exhausted")
            # 호출 전에 예약을 기록해 중단·timeout도 전체 승인 횟수에 포함함.
            with self.ledger.open("a", encoding="utf-8") as output:
                output.write(json.dumps({"kind": "model", "phase": "reserved", "model": payload["model"]}) + "\n")
        started = time.monotonic()
        event = {"kind": "model" if model_call else "control", "path": url.split("/internal/ai-users")[-1] if not model_call else "messages"}
        if model_call:
            state = json.loads(payload["messages"][0]["content"])["state"]
            event.update(model=payload["model"], persona=state.get("personaKey"),
                         source=state.get("source", {}).get("type"), memories=state.get("memories", []),
                         allowedActions=state.get("actions"), onboarding=state.get("onboarding"))
        elif url.endswith("/execute"):
            event.update(action=payload["action"], payload=payload["payload"], memory=payload.get("memory"))
        try:
            result = super().post(url, payload, headers, timeout)
            if model_call:
                event.update(usage=result.get("usage"), stop_reason=result.get("stop_reason"), content=result.get("content"))
            elif not url.endswith("/snapshot"):
                # 인증 정보나 전체 snapshot을 기록하지 않음. 가상 테스트 응답만 저장함.
                event["response"] = result
            return result
        except Exception as error:
            self.failed = True
            event["error"] = type(error).__name__
            if isinstance(error, HTTPError):
                event["status"] = error.code
                event["body"] = error.read(2000).decode("utf-8", errors="replace")
                if model_call and error.code in (401, 403, 404, 429):
                    self.fatal = True
            raise
        finally:
            event["seconds"] = round(time.monotonic() - started, 3)
            self.write(event)


def main():
    raise SystemExit("기존 직접 Claude 호출 평가는 종료됨. Graphiti 경로는 별도 호출 예산을 승인한 뒤 평가해야 함.")


if __name__ == "__main__":
    main()
