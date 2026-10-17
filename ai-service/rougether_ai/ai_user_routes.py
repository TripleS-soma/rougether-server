from fastapi import APIRouter, Request

from .ai_user_models import DecisionRequest, EpisodeRequest, ForgetRequest, InventoryRequest, RecallRequest
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
    return await service(request).decide(body)


@router.post("/forget")
async def forget(body: ForgetRequest, request: Request):
    active = service(request)
    async with active.operation():
        return await active.memory.forget(body)
