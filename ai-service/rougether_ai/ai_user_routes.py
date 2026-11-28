from fastapi import APIRouter, Request

from .ai_user_models import DecisionRequest, EpisodeRequest, ForgetRequest, InventoryRequest, RecallRequest
from .ai_user_policy import DECISION_FORMAT_HEADER, VERSIONED_DECISION_FORMAT
from .provider import AiError

router = APIRouter(prefix="/internal/v1/ai-users")


def service(request):
    active = request.app.state.ai_users
    if active is None:
        raise AiError("AI_USERS_DISABLED", 503)
    return active


@router.post("/recall")
async def recall(body: RecallRequest, request: Request):
    active = service(request)
    async with active.operation():
        return await active.memory.recall(body)


@router.post("/inventory")
async def inventory(body: InventoryRequest, request: Request):
    active = service(request)
    async with active.operation():
        return await active.memory.inventory(body)


@router.post("/episodes")
async def ingest(body: EpisodeRequest, request: Request):
    active = service(request)
    async with active.operation():
        return await active.memory.ingest(body)


@router.post("/decide")
async def decide(body: DecisionRequest, request: Request):
    # 본문 모델은 extra="forbid"라 새 필드를 넣으면 구형 서비스가 422를 냄. 그래서 확장 응답은 헤더로 요청받음.
    versioned = request.headers.get(DECISION_FORMAT_HEADER) == VERSIONED_DECISION_FORMAT
    return await service(request).decide(body, versioned=versioned)


@router.post("/forget")
async def forget(body: ForgetRequest, request: Request):
    active = service(request)
    async with active.operation():
        return await active.memory.forget(body)
