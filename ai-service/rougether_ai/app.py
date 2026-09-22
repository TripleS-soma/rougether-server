import asyncio
import hmac
import logging
import time
import uuid
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.responses import JSONResponse

from .config import Settings
from .models import CompletionRequest, EmbeddingRequest
from .provider import AiError, Provider

log = logging.getLogger("uvicorn.error.rougether")


class InternalBoundary:
    def __init__(self, app, settings):
        self.app, self.settings = app, settings

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/internal/"):
            return await self.app(scope, receive, send)
        request_id = str(uuid.uuid4())
        headers = dict(scope["headers"])
        expected = ("Bearer " + self.settings.service_token).encode()

        async def reject(status, code):
            await JSONResponse({"code": code}, status_code=status,
                headers={"X-Request-Id": request_id, "Cache-Control": "no-store"})(scope, receive, send)

        if not hmac.compare_digest(headers.get(b"authorization", b""), expected):
            return await reject(401, "AI_UNAUTHORIZED")
        if scope["method"] != "POST":
            return await reject(405, "AI_METHOD_NOT_ALLOWED")
        if headers.get(b"content-type", b"").split(b";", 1)[0].strip().lower() != b"application/json":
            return await reject(415, "AI_INVALID_CONTENT_TYPE")
        body = bytearray()
        try:
            async with asyncio.timeout(10):
                while True:
                    event = await receive()
                    if event["type"] == "http.disconnect":
                        return
                    chunk = event.get("body", b"")
                    if len(body) + len(chunk) > self.settings.max_request_bytes:
                        return await reject(413, "AI_REQUEST_TOO_LARGE")
                    body.extend(chunk)
                    if not event.get("more_body", False):
                        break
        except TimeoutError:
            return await reject(408, "AI_REQUEST_TIMEOUT")
        delivered = False
        started = time.monotonic()
        status = 500

        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        async def respond(event):
            nonlocal status
            if event["type"] == "http.response.start":
                status = event["status"]
                event["headers"] = list(event.get("headers", [])) + [
                    (b"x-request-id", request_id.encode()), (b"cache-control", b"no-store")]
            await send(event)

        try:
            await self.app(scope, replay, respond)
        finally:
            # 본문·자격 증명·사용자 식별자를 로그에 넣지 않음.
            operation = {"/internal/v1/embeddings": "embeddings", "/internal/v1/completions": "completions"}.get(scope["path"], "unknown")
            log.info("request_id=%s operation=%s status=%s duration_ms=%d", request_id,
                operation, status, (time.monotonic() - started) * 1000)


def create_app(settings: Settings | None = None, transport: httpx.AsyncBaseTransport | None = None):
    config = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(
            base_url=config.provider_base_url.rstrip("/") + "/",
            headers={"Authorization": "Bearer " + config.provider_api_key, "Accept": "application/json"},
            timeout=httpx.Timeout(config.provider_timeout_seconds, connect=5, pool=1, write=10),
            limits=httpx.Limits(max_connections=config.chat_concurrency + config.embedding_concurrency,
                               max_keepalive_connections=config.chat_concurrency + config.embedding_concurrency),
            follow_redirects=False, trust_env=False, transport=transport,
        ) as client:
            app.state.provider = Provider(config, client)
            yield

    app = FastAPI(title="Rougether AI", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(InternalBoundary, settings=config)

    @app.exception_handler(AiError)
    async def ai_error(request: Request, error: AiError):
        headers = {"Retry-After": "1"} if error.status == 429 else None
        return JSONResponse({"code": error.code}, status_code=error.status, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError):
        # Pydantic 기본 오류 응답의 input에 프롬프트가 노출되지 않게 고정 코드만 반환함.
        return JSONResponse({"code": "AI_INVALID_REQUEST"}, status_code=422)

    @app.get("/health/live")
    async def live():
        return {"status": "ok", "service": "rougether-ai"}

    @app.get("/health/ready")
    async def ready(request: Request):
        return {"status": "ok", "service": "rougether-ai"}

    @app.post("/internal/v1/embeddings")
    async def embeddings(body: EmbeddingRequest, request: Request):
        return await request.app.state.provider.embeddings(body)

    @app.post("/internal/v1/completions")
    async def completions(body: CompletionRequest, request: Request):
        return await request.app.state.provider.complete(body)

    return app
