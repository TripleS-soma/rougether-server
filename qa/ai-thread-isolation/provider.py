"""지연만 주입하는 로컬 모의 공급자. 실제 AI/DB/인증 서비스와 연결하지 않음."""
import asyncio
from fastapi import FastAPI, Request

app = FastAPI()
state = {"delay": 0.02, "active": 0, "peak": 0, "started": 0, "completed": 0}


@app.post("/configure")
async def configure(request: Request):
    body = await request.json()
    assert state["active"] == 0
    state.update(delay=float(body["delay"]), peak=0, started=0, completed=0)
    return state


@app.get("/metrics")
async def metrics():
    return state


@app.post("/v1/embeddings")
async def embeddings(request: Request):
    assert request.headers["authorization"] == "Bearer probe-provider-key"
    body = await request.json()
    assert body["model"] == "probe-embedding" and body["dimensions"] == 2
    state["active"] += 1
    state["started"] += 1
    state["peak"] = max(state["peak"], state["active"])
    try:
        await asyncio.sleep(state["delay"])
        state["completed"] += 1
        return {"data": [{"index": index, "embedding": [0.1, 0.2]} for index, _ in enumerate(body["input"])]}
    finally:
        state["active"] -= 1
