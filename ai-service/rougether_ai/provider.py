import asyncio
import json
import math
from contextlib import asynccontextmanager

import httpx

from .config import Settings


class AiError(Exception):
    def __init__(self, code: str, status: int, retryable: bool = False):
        super().__init__(code)
        self.code, self.status, self.retryable = code, status, retryable


class Provider:
    def __init__(self, settings: Settings, client: httpx.AsyncClient):
        self.settings, self.client = settings, client
        self.chat_slots = asyncio.Semaphore(settings.chat_concurrency)
        self.embedding_slots = asyncio.Semaphore(settings.embedding_concurrency)

    @asynccontextmanager
    async def slot(self, slots):
        # 대기열을 쌓지 않고 포화 시 즉시 실패함. 한 이벤트 루프에서 검사와 acquire 사이에 양보하지 않음.
        if slots.locked():
            raise AiError("AI_CAPACITY_EXCEEDED", 429, True)
        await slots.acquire()
        try:
            async with asyncio.timeout(self.settings.request_timeout_seconds):
                yield
        except TimeoutError:
            raise AiError("AI_DEADLINE_EXCEEDED", 504, True) from None
        finally:
            slots.release()

    async def post(self, path, payload):
        # 공급자 재시도는 이 계층만 소유함. Spring 내부 HTTP 클라이언트는 재전송하지 않음.
        for attempt in range(self.settings.max_retries + 1):
            try:
                async with self.client.stream("POST", path, json=payload) as response:
                    status = response.status_code
                    if status in (401, 403):
                        raise AiError("AI_PROVIDER_AUTH_FAILED", 502)
                    if status == 429 or status >= 500:
                        raise AiError("AI_PROVIDER_UNAVAILABLE", 503, True)
                    if status >= 300:
                        raise AiError("AI_PROVIDER_REJECTED", 502)
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        if len(body) + len(chunk) > self.settings.max_response_bytes:
                            raise AiError("AI_INVALID_RESPONSE", 502)
                        body.extend(chunk)
                    try:
                        data = json.loads(body)
                        if not isinstance(data, dict):
                            raise ValueError()
                        return data
                    except (ValueError, UnicodeError):
                        raise AiError("AI_INVALID_RESPONSE", 502) from None
            except httpx.RequestError:
                error = AiError("AI_PROVIDER_UNAVAILABLE", 503, True)
            except AiError as exc:
                error = exc
            if not error.retryable or attempt == self.settings.max_retries:
                raise error from None
            await asyncio.sleep(self.settings.retry_backoff_seconds * 2**attempt)

    async def embeddings(self, request):
        if request.model != self.settings.embedding_model or request.dimensions != self.settings.embedding_dimensions:
            raise AiError("AI_MODEL_MISMATCH", 409)
        async with self.slot(self.embedding_slots):
            data = await self.post("embeddings", {"model": request.model, "input": request.inputs,
                "dimensions": request.dimensions, "encoding_format": "float"})
            vectors = [None] * len(request.inputs)
            rows = data.get("data")
            if not isinstance(rows, list) or len(rows) != len(vectors):
                raise AiError("AI_INVALID_RESPONSE", 502)
            for row in rows:
                if not isinstance(row, dict):
                    raise AiError("AI_INVALID_RESPONSE", 502)
                index, vector = row.get("index"), row.get("embedding")
                if type(index) is not int or not 0 <= index < len(vectors) or vectors[index] is not None:
                    raise AiError("AI_INVALID_RESPONSE", 502)
                if not isinstance(vector, list) or len(vector) != request.dimensions:
                    raise AiError("AI_INVALID_RESPONSE", 502)
                try:
                    valid = all(type(v) in (int, float) and math.isfinite(v) for v in vector)
                except OverflowError:
                    valid = False
                if not valid:
                    raise AiError("AI_INVALID_RESPONSE", 502)
                vectors[index] = vector
            return {"model": request.model, "vectors": vectors}

    async def complete(self, request):
        if request.model != self.settings.chat_model:
            raise AiError("AI_MODEL_MISMATCH", 409)
        if not request.userPrompt.strip():
            raise AiError("AI_INVALID_REQUEST", 422)
        messages = []
        if request.systemPrompt and request.systemPrompt.strip():
            messages.append({"role": "system", "content": request.systemPrompt})
        messages.append({"role": "user", "content": request.userPrompt})
        payload = {"model": request.model, "messages": messages, "max_completion_tokens": request.maxTokens}
        if request.temperature is not None:
            payload["temperature"] = request.temperature
        if request.reasoningEffort:
            payload["reasoning_effort"] = request.reasoningEffort
        if request.jsonMode:
            payload["response_format"] = {"type": "json_object"}
        async with self.slot(self.chat_slots):
            data = await self.post("chat/completions", payload)
            try:
                content = data["choices"][0]["message"]["content"]
                if not isinstance(content, str) or not content.strip() or len(content) > 131072:
                    raise ValueError()
            except (KeyError, IndexError, TypeError, ValueError):
                raise AiError("AI_INVALID_RESPONSE", 502) from None
            return {"model": request.model, "content": content}
