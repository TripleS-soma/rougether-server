"""실제 Neo4j와 Graphiti를 사용하며 모델 출력만 고정된 합성 데이터로 대체함."""
import json
import os
from datetime import datetime, timedelta, timezone

import pytest

pytest.importorskip("graphiti_core")
from rougether_ai.ai_user_models import EpisodeRequest, ForgetRequest, InventoryRequest, RecallRequest
from rougether_ai.ai_user_settings import AiUserSettings
from rougether_ai.graph_memory import GraphMemory

pytestmark = pytest.mark.skipif(not os.getenv("AI_USER_TEST_NEO4J_URI"), reason="격리 Neo4j 테스트 실행기로 기동함")


class FixtureClaude:
    settings = type("Config", (), {"memory_model": "fixture-no-api"})()
    def __init__(self):
        self.person, self.title, self.calls = "user:42", "달빛서랍", 0
        self.action = False

    async def generate(self, model, system, prompt, max_tokens):
        self.calls += 1
        schema = json.loads(system.split("JSON schema: ", 1)[1])
        name = schema.get("title")
        if name == "ExtractedEntities":
            self.action = "speakerAiUserId" in prompt
        names = ["ai:3", "comment:304"] if self.action else [self.person, self.title]
        if name == "ExtractedEntities":
            data = {"extracted_entities": [{"name": n, "entity_type_id": 0} for n in names]}
        elif name == "ExtractedEdges":
            data = {"edges": [{"source_entity_name": names[0], "target_entity_name": names[1],
                "relation_type": "POSTED_COMMENT" if self.action else "RECORD_TITLE",
                "fact": "ai:3이 comment:304에 기록을 이어가요라고 작성함" if self.action else f"{self.person}의 독서 기록 이름은 {self.title}"}]}
        elif name == "EntitySummary":
            data = {"summary": "원문에 기록된 합성 인물 또는 기록 이름"}
        elif name == "SummarizedEntities":
            data = {"summaries": [{"name": n, "summary": n} for n in names]}
        elif name == "NodeResolutions":
            data = {"entity_resolutions": [{"id": i, "name": n, "duplicate_candidate_id": -1} for i,n in enumerate(names)]}
        elif name == "EdgeDuplicate":
            data = {"duplicate_facts": [], "contradicted_facts": []}
        elif name == "EdgeTimestamps":
            data = {"valid_at": None, "invalid_at": None}
        else:
            raise AssertionError(f"Unexpected Graphiti extraction schema: {schema}")
        return {"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}]}


def episode(user, job, text):
    return EpisodeRequest(aiUserId=user, jobId=job, body=text,
        occurredAt=datetime.now(timezone.utc)-timedelta(days=40),
        sources=[{"type": "FEED_COMMENT", "id": job, "fingerprint": "a"*64}])


async def test_real_graphiti_ingestion_namespace_replay_and_recall_after_restart():
    config = AiUserSettings(os.environ["AI_USER_TEST_NEO4J_URI"], os.environ["AI_USER_TEST_NEO4J_PASSWORD"], "unused-test-key")
    llm = FixtureClaude()
    memory = await GraphMemory.create(config, llm)
    try:
        first = episode(1, 101, '{"author_id":42,"content":"내 독서 기록 이름은 달빛서랍이야"}')
        result = await memory.ingest(first)
        calls = llm.calls
        replay = await memory.ingest(first)
        assert replay["replayed"] and replay["episodeId"] == result["episodeId"]
        assert llm.calls == calls
        facts = await memory.recall(RecallRequest(aiUserId=1, query="달빛서랍 독서 기록 이름"))
        assert any("달빛서랍" in f["text"] for f in facts)
        assert await memory.recall(RecallRequest(aiUserId=2, query="달빛서랍 독서 기록 이름")) == []
        # 최근 12개 창을 넘긴 뒤에도 관련 사실은 장기 검색으로 회수해야 함.
        for number in range(100):
            llm.person,llm.title=f"user:{5000+number}",f"기상관측_{number}"
            await memory.ingest(episode(1,1000+number,json.dumps({"author_id":5000+number,"content":llm.title})))
        old=await memory.recall(RecallRequest(aiUserId=1,query="user:42 달빛서랍 독서 기록 이름"))
        assert any("달빛서랍" in f["text"] for f in old)
        llm.person, llm.title = "user:99", "별빛노트"
        await memory.ingest(episode(2, 102, '{"author_id":99,"content":"내 기록은 별빛노트야"}'))
        await memory.close()
        memory = await GraphMemory.create(config, llm)
        facts = await memory.recall(RecallRequest(aiUserId=1, counterpartUserId=42, query="독서 기록 이름"))
        assert any("달빛서랍" in f["text"] for f in facts)
        assert all("별빛노트" not in f["text"] for f in facts)
        # 다른 계정의 삭제 요청은 원래 계정의 episode를 지우지 못함.
        await memory.forget(ForgetRequest(aiUserId=2, episodes=[result["episodeId"]]))
        assert await memory.recall(RecallRequest(aiUserId=1, query="달빛서랍"))
        await memory.forget(ForgetRequest(aiUserId=1, episodes=[result["episodeId"]]))
        assert all("달빛서랍" not in f["text"] for f in await memory.recall(RecallRequest(aiUserId=1, query="달빛서랍")))
    finally:
        await memory.close()


async def test_observation_has_its_own_provenance_and_each_segment_replays_once():
    config=AiUserSettings(os.environ["AI_USER_TEST_NEO4J_URI"],os.environ["AI_USER_TEST_NEO4J_PASSWORD"],"unused-test-key")
    llm=FixtureClaude()
    llm.title="햇살기록"
    memory=await GraphMemory.create(config,llm)
    try:
        data={"aiUserId":3,"sourceType":"FEED_COMMENT","primarySourceId":303,"includeAction":True,
            "observation":{"author_id":42,"content":"내 기록은 햇살기록이야"},
            "action":"FEED_COMMENT","payload":{"content":"기록을 이어가요"},"receipt":{"commentId":304}}
        request=episode(3,303,json.dumps(data,ensure_ascii=False))
        request.sources.append(type(request.sources[0])(type="FEED_POST",id=900,fingerprint="b"*64))
        result=await memory.ingest(request)
        assert len(result["episodeIds"])==2
        calls=llm.calls
        assert (await memory.ingest(request))["replayed"]
        assert llm.calls==calls
        facts=await memory.recall(RecallRequest(aiUserId=3,counterpartUserId=42,query="햇살기록"))
        assert any(len(fact["sources"])==1 and fact["sources"][0]["id"]==303 for fact in facts)
    finally:
        await memory.close()


async def test_failed_extraction_is_not_recalled_and_is_available_for_cleanup():
    config = AiUserSettings(os.environ["AI_USER_TEST_NEO4J_URI"], os.environ["AI_USER_TEST_NEO4J_PASSWORD"], "unused-test-key")
    llm = FixtureClaude()
    memory = await GraphMemory.create(config, llm)
    async def unavailable(*args, **kwargs):
        raise RuntimeError("synthetic provider unavailable")
    llm.generate = unavailable
    try:
        with pytest.raises(RuntimeError):
            await memory.ingest(episode(4, 404, '{"author_id":42,"content":"미완료 원문"}'))
        assert await memory.recall(RecallRequest(aiUserId=4, query="미완료 원문")) == []
        records = (await memory.inventory(InventoryRequest(aiUserId=4)))["records"]
        assert len(records) == 1 and records[0]["sources"] == []
        await memory.forget(ForgetRequest(aiUserId=4, episodes=[records[0]["episode"]]))
        assert (await memory.inventory(InventoryRequest(aiUserId=4)))["records"] == []
        # 단순 주기 대기는 추출 모델이 실패한 상태에서도 유료 호출 없이 반영 완료됨.
        idle = episode(4, 405, json.dumps({"sourceType":"SCHEDULE","primarySourceId":0,
            "includeAction":True,"observation":{},"action":"WAIT","payload":{},"receipt":{}}))
        assert (await memory.ingest(idle))["episodeIds"] == []
        assert (await memory.inventory(InventoryRequest(aiUserId=4)))["records"] == []
    finally:
        await memory.close()
