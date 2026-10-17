import asyncio
import json
from dataclasses import replace
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient

from rougether_ai.ai_user_models import DecisionRequest
from rougether_ai.ai_user_service import AiUserService, ClaudeGateway
from rougether_ai.ai_user_settings import AiUserSettings
from rougether_ai.app import create_app
from rougether_ai.config import Settings

TOKEN = "test-ai-service-token-at-least-32-chars"
CONFIG = Settings(service_token=TOKEN, provider_api_key="unused-existing-provider-key", ai_users_enabled=True)
MEMORY_CONFIG = AiUserSettings("bolt://localhost:7687", "test-only", "test-anthropic-key")
AUTH = {"Authorization": "Bearer " + TOKEN}
SOURCE = {"type": "FEED_COMMENT", "id": 101, "fingerprint": "a" * 64}
FACT = {"text": "user:42의 독서 기록 이름은 달빛서랍", "sources": [SOURCE], "episodes": ["00000000-0000-0000-0000-000000000001"]}


class Memory:
    def __init__(self):
        self.seen = []
    async def recall(self, request):
        self.seen.append(request.aiUserId)
        return [FACT] if request.aiUserId == 1 else []
    async def ingest(self, request):
        self.seen.append(request.aiUserId)
        return {"episodeId": "test"}
    async def forget(self, request):
        self.seen.append(request.aiUserId)
        return {"ok": True}
    async def close(self):
        pass


def active(handler=lambda r: pytest.fail("unexpected model call")):
    gateway = ClaudeGateway(MEMORY_CONFIG, httpx.MockTransport(handler))
    return AiUserService(MEMORY_CONFIG, Memory(), gateway)


@pytest.mark.parametrize("path,payload", [
    ("recall", {"aiUserId": 1, "query": "기억"}),
    ("decide", {"aiUserId": 1, "state": {"aiUserId": 1, "actions": ["WAIT"]}}),
    ("episodes", {}), ("forget", {})])
def test_ai_routes_require_service_auth_before_validation(path, payload):
    svc = active()
    with TestClient(create_app(CONFIG, ai_users=svc)) as client:
        response = client.post("/internal/v1/ai-users/" + path, json=payload)
        assert response.status_code == 401
        assert not svc.memory.seen


def test_episode_accepts_timezone_and_rejects_unknown_owner_scope():
    svc=active()
    with TestClient(create_app(CONFIG,ai_users=svc)) as client:
        payload={"aiUserId":1,"jobId":100,"body":"{\"content\":\"합성 원문\"}","occurredAt":"2026-10-17T01:00:00Z","sources":[SOURCE]}
        assert client.post("/internal/v1/ai-users/episodes",json=payload,headers=AUTH).status_code==200
        assert svc.memory.seen==[1]
        payload["group_id"]="ai_user_2"
        assert client.post("/internal/v1/ai-users/episodes",json=payload,headers=AUTH).status_code==422


def test_memory_routes_disabled_by_default():
    with TestClient(create_app(replace(CONFIG, ai_users_enabled=False))) as client:
        response = client.post("/internal/v1/ai-users/recall", json={"aiUserId": 1, "query": "독서"}, headers=AUTH)
        assert response.status_code == 503


def test_recall_and_input_scope_cannot_override_group_id():
    with TestClient(create_app(CONFIG, ai_users=active())) as client:
        for user, expected in [(1, [FACT]), (2, [])]:
            assert client.post("/internal/v1/ai-users/recall", json={"aiUserId": user, "query": "독서"}, headers=AUTH).json() == expected
        for body in [{"aiUserId": 1, "query": "독서", "group_ids": ["ai_user_2"]}, {"aiUserId": "1", "query": "독서"}]:
            assert client.post("/internal/v1/ai-users/recall", json=body, headers=AUTH).status_code == 422


def test_decision_uses_human_model_and_source_checked_facts():
    calls = []
    def handler(request):
        calls.append(json.loads(request.content))
        assert request.url.host == "api.anthropic.com"
        return httpx.Response(200, json={"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps({
            "action": "FEED_COMMENT", "payload": {"postId": 10, "content": "달빛서랍 기록은 어때요?"}, "memory": "독서 기록을 물어봄"})}]})
    with TestClient(create_app(CONFIG, ai_users=active(handler))) as client:
        response = client.post("/internal/v1/ai-users/decide", headers=AUTH, json={"aiUserId": 1,
            "state": {"aiUserId": 1, "actions": ["FEED_COMMENT"], "source": {"type": "FEED_COMMENT"}}, "facts": [FACT]})
        assert response.status_code == 200
        assert response.json()["payload"]["postId"] == 10
    assert len(calls) == 1
    assert calls[0]["model"] == "claude-sonnet-5-5"
    assert calls[0]["thinking"] == {"type": "between_tools"}
    assert json.loads(calls[0]["messages"][0]["content"])["state"]["longTermMemories"] == [{"text":FACT["text"]}]


def test_account_mismatch_and_unknown_actions_never_call_model():
    with TestClient(create_app(CONFIG, ai_users=active())) as client:
        for state in [{"aiUserId": 2, "actions": ["WAIT"]}, {"aiUserId": 1, "actions": ["SQL"]}]:
            assert client.post("/internal/v1/ai-users/decide", json={"aiUserId": 1, "state": state}, headers=AUTH).status_code == 422


def test_deterministic_action_requires_no_model():
    with TestClient(create_app(CONFIG, ai_users=active())) as client:
        result = client.post("/internal/v1/ai-users/decide", headers=AUTH, json={"aiUserId": 1,
            "state": {"aiUserId": 1, "actions": ["WAIT"], "source": {"type": "SCHEDULE"},
                      "localTime": "03:00", "personaKey": "steady-walk"}})
        assert result.json() == {"action": "WAIT", "payload": {}, "memory": ""}


def test_provider_error_does_not_retry_or_leak_response():
    calls = []
    def fail(request):
        calls.append(request)
        return httpx.Response(500, text="private human conversation and secret")
    with TestClient(create_app(CONFIG, ai_users=active(fail))) as client:
        response = client.post("/internal/v1/ai-users/decide", headers=AUTH,
            json={"aiUserId": 1, "state": {"aiUserId": 1, "actions": ["WAIT"]}})
        assert response.status_code == 503
        assert "private" not in response.text and "secret" not in response.text
        assert len(calls) == 1


async def test_memory_and_decisions_share_one_execution_slot():
    svc = active()
    async with svc.operation():
        with pytest.raises(Exception) as error:
            await svc.decide(DecisionRequest(aiUserId=1, state={"aiUserId": 1, "actions": ["WAIT"]}))
        assert error.value.status == 429
    await svc.close()
