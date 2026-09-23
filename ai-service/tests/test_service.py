import asyncio
import json
from contextlib import contextmanager
from dataclasses import replace

import httpx
import pytest
from fastapi.testclient import TestClient

from rougether_ai.app import create_app
from rougether_ai.config import Settings

TOKEN = "test-internal-token-12345678901234567890"
SETTINGS = Settings(service_token=TOKEN, provider_api_key="provider-key-not-in-response", embedding_dimensions=2, retry_backoff_seconds=0)
AUTH = {"Authorization": "Bearer " + TOKEN}
EMBED = {"model": SETTINGS.embedding_model, "dimensions": 2, "inputs": ["운동", "독서"]}
CHAT = {"model": SETTINGS.chat_model, "systemPrompt": "Answer in English JSON", "userPrompt": "My week", "maxTokens": 800}


def embedding_response():
    return httpx.Response(200, json={"data": [
        {"index": 1, "embedding": [0.3, 0.4]}, {"index": 0, "embedding": [0.1, 0.2]}]})


@contextmanager
def client(handler, settings=SETTINGS):
    app = create_app(settings, httpx.MockTransport(handler))
    with TestClient(app) as c:
        yield c


def test_authentication_precedes_json_validation_and_provider_call():
    def fail(request):
        pytest.fail("unauthorized request reached provider")
    with client(fail) as c:
        for headers in [{}, {"Authorization": "Bearer wrong"}]:
            r = c.post("/internal/v1/embeddings", content="private invalid prompt", headers=headers)
            assert r.status_code == 401
            assert r.json() == {"code": "AI_UNAUTHORIZED"}
            assert "private" not in r.text
        assert c.get("/health/live").status_code == 200
        assert c.get("/docs").status_code == 404


def test_embeddings_keep_order_and_use_provider_credential_only():
    def handler(request):
        assert request.url.path == "/v1/embeddings"
        assert request.headers["Authorization"] == "Bearer " + SETTINGS.provider_api_key
        body = json.loads(request.content)
        assert body == {"model": SETTINGS.embedding_model, "input": EMBED["inputs"], "dimensions": 2, "encoding_format": "float"}
        return embedding_response()
    with client(handler) as c:
        r = c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH)
        assert r.status_code == 200
        assert r.json() == {"model": SETTINGS.embedding_model, "vectors": [[0.1, 0.2], [0.3, 0.4]]}
        assert r.headers["cache-control"] == "no-store"
        assert r.headers["x-request-id"]


def test_completion_keeps_prompts_options_and_content():
    def handler(request):
        body = json.loads(request.content)
        assert request.url.path == "/v1/chat/completions"
        assert body["messages"] == [{"role": "system", "content": CHAT["systemPrompt"]}, {"role": "user", "content": CHAT["userPrompt"]}]
        assert body["max_completion_tokens"] == 800
        assert body["response_format"] == {"type": "json_object"}
        assert body["reasoning_effort"] == "low"
        assert "temperature" not in body
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"summary":"Great week"}'}}]})
    with client(handler) as c:
        r = c.post("/internal/v1/completions", json=CHAT, headers=AUTH)
        assert r.json() == {"model": SETTINGS.chat_model, "content": '{"summary":"Great week"}'}


@pytest.mark.parametrize("status,expected,attempts,code", [
    (401, 502, 1, "AI_PROVIDER_AUTH_FAILED"), (403, 502, 1, "AI_PROVIDER_AUTH_FAILED"),
    (400, 502, 1, "AI_PROVIDER_REJECTED"), (302, 502, 1, "AI_PROVIDER_REJECTED"),
    (429, 503, 3, "AI_PROVIDER_UNAVAILABLE"), (500, 503, 3, "AI_PROVIDER_UNAVAILABLE")])
def test_retry_ownership_and_safe_error(status, expected, attempts, code):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(status, text="provider secret and private prompt", headers={"Location": "https://untrusted.invalid"})
    with client(handler) as c:
        r = c.post("/internal/v1/completions", json=CHAT, headers=AUTH)
        assert r.status_code == expected
        assert r.json() == {"code": code}
        assert len(calls) == attempts
        assert "secret" not in r.text


def test_transient_provider_failure_then_success():
    calls = []
    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            raise httpx.ConnectError("connection failed", request=request)
        return embedding_response()
    with client(handler) as c:
        assert c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH).status_code == 200
        assert len(calls) == 2


@pytest.mark.parametrize("payload", [
    {**EMBED, "inputs": ["a"] * 129}, {**EMBED, "inputs": ["  "]},
    {**EMBED, "inputs": ["a" * 2049]}, {**EMBED, "dimensions": "2"},
    {**EMBED, "url": "https://untrusted.invalid"}, {**EMBED, "inputs": []},
])
def test_invalid_input_never_reaches_provider(payload):
    with client(lambda r: pytest.fail("invalid request reached provider")) as c:
        r = c.post("/internal/v1/embeddings", json=payload, headers=AUTH)
        assert r.status_code == 422
        assert r.json() == {"code": "AI_INVALID_REQUEST"}


def test_model_mismatch_and_size_limit():
    with client(lambda r: pytest.fail("request reached provider"), replace(SETTINGS, max_request_bytes=512)) as c:
        r = c.post("/internal/v1/embeddings", json={**EMBED, "dimensions": 3}, headers=AUTH)
        assert r.status_code == 409
        assert r.json()["code"] == "AI_MODEL_MISMATCH"
        r = c.post("/internal/v1/completions", json={**CHAT, "userPrompt": "x" * 600}, headers=AUTH)
        assert r.status_code == 413


@pytest.mark.parametrize("rows", [
    [], [{"index": 0, "embedding": [1, 2]}],
    [{"index": 0, "embedding": [1, 2]}, {"index": 0, "embedding": [3, 4]}],
    [{"index": 0, "embedding": [1]}, {"index": 1, "embedding": [3, 4]}],
    [{"index": 0, "embedding": [True, 2]}, {"index": 1, "embedding": [3, 4]}],
    [{"index": 0, "embedding": ["1", 2]}, {"index": 1, "embedding": [3, 4]}],
    [{"index": 0, "embedding": [10**400, 2]}, {"index": 1, "embedding": [3, 4]}],
    [None, {"index": 1, "embedding": [3, 4]}],
])
def test_malformed_vectors_fail_closed_without_retry(rows):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"data": rows})
    with client(handler) as c:
        r = c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH)
        assert r.status_code == 502
        assert r.json() == {"code": "AI_INVALID_RESPONSE"}
        assert len(calls) == 1


def test_provider_response_size_limit():
    with client(lambda r: httpx.Response(200, content=b"x" * 101), replace(SETTINGS, max_response_bytes=100)) as c:
        r = c.post("/internal/v1/completions", json=CHAT, headers=AUTH)
        assert r.json() == {"code": "AI_INVALID_RESPONSE"}


async def test_embedding_saturation_does_not_block_completion_or_health():
    entered, release = asyncio.Event(), asyncio.Event()
    async def handler(request):
        if request.url.path.endswith("embeddings"):
            entered.set()
            await release.wait()
            return embedding_response()
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})
    app = create_app(replace(SETTINGS, embedding_concurrency=1), httpx.MockTransport(handler))
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
            pending = asyncio.create_task(c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH))
            await asyncio.wait_for(entered.wait(), 1)
            saturated = await c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH)
            assert saturated.status_code == 429
            assert saturated.headers["Retry-After"] == "1"
            assert (await c.post("/internal/v1/completions", json=CHAT, headers=AUTH)).status_code == 200
            assert (await c.get("/health/ready")).status_code == 200
            release.set()
            assert (await pending).status_code == 200


async def test_deadline_and_cancellation_release_capacity():
    calls = 0
    async def handler(request):
        nonlocal calls
        calls += 1
        if calls < 3:
            await asyncio.sleep(10)
        return embedding_response()
    config = replace(SETTINGS, embedding_concurrency=1, provider_timeout_seconds=0.02, request_timeout_seconds=0.05)
    app = create_app(config, httpx.MockTransport(handler))
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
            r = await c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH)
            assert r.status_code == 504
            pending = asyncio.create_task(c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH))
            while calls < 2:
                await asyncio.sleep(0)
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            assert (await c.post("/internal/v1/embeddings", json=EMBED, headers=AUTH)).status_code == 200


@pytest.mark.parametrize("changes", [
    {"service_token": "short"}, {"provider_api_key": ""},
    {"service_token": "한" * 32}, {"service_token": "a" * 32 + "\u0001"},
    {"provider_base_url": "http://public.example.com/v1"},
    {"provider_base_url": "https://user:secret@example.com/v1"},
    {"embedding_dimensions": 0}, {"max_retries": 100}, {"chat_concurrency": 0},
])
def test_bad_config_fails_at_startup(changes):
    with pytest.raises(ValueError):
        replace(SETTINGS, **changes)
    assert TOKEN not in repr(SETTINGS)
    assert SETTINGS.provider_api_key not in repr(SETTINGS)
