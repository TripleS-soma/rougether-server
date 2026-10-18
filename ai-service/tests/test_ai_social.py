import json

import httpx
import pytest
from fastapi.testclient import TestClient
from rougether_ai.ai_user_policy import ACTION_HELP, code_plan, parse_plan, planning_actions
from rougether_ai.app import create_app
from test_ai_users import AUTH, CONFIG, active


def social_state():
    return {"aiUserId": 1, "personaKey": "social-evening", "persona": "사교형, 저녁 대화",
            "source": {"type": "SOCIAL_BROWSE"}, "date": "2026-10-17", "localTime": "20:30:00",
            "onboarding": {"goals": [1], "selectedCharacterId": 1},
            "actions": ["FEED_POST", "FEED_COMMENT", "FEED_LIKE", "WAIT"],
            "social": {"allowedActions": ["FEED_POST", "FEED_COMMENT", "FEED_LIKE", "WAIT"],
                       "candidates": [{"postId": 7, "content": "독서 기록을 어떻게 정리하시나요?", "recentComments": []}],
                       "recentActions": [], "preference": "기존 글에 구체적인 댓글로 참여"},
            "today": {"categories": [{"routines": [{"id": 1, "authType": "CHECK", "completed": False}]}]}}


def test_social_visit_is_not_preempted_by_due_routine_and_targets_existing_post():
    state = social_state()
    state["actions"] = planning_actions(state)
    assert code_plan(state) is None
    calls = []
    def handler(request):
        body = json.loads(request.content)
        prompt = json.loads(body["messages"][0]["content"])
        calls.append(prompt)
        assert "ROUTINE_COMPLETE" not in prompt["actionFormats"]
        assert prompt["state"]["social"]["candidates"][0]["postId"] == 7
        assert "SOCIAL_BROWSE" in body["system"]
        return httpx.Response(200, json={"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps({
            "action": "FEED_COMMENT", "payload": {"postId": 7, "content": "저는 기록할 때 주제를 나눠볼까 고민 중이에요. 분류도 하고 계세요?"}, "memory": "기존 독서 글에 참여함"})}]})
    with TestClient(create_app(CONFIG, ai_users=active(handler))) as client:
        response = client.post("/internal/v1/ai-users/decide", headers=AUTH, json={"aiUserId": 1, "state": state})
        assert response.status_code == 200
        assert response.json()["action"] == "FEED_COMMENT"
        assert response.json()["payload"]["postId"] == 7
    assert len(calls) == 1


def test_visit_budget_and_onboarding_record_wait_without_provider():
    for reason in ["DAILY_LIMIT", "NO_CANDIDATE", "COOLDOWN"]:
        state = social_state()
        state["actions"] = ["WAIT"]
        state["social"].update(allowedActions=["WAIT"], unavailableReason=reason)
        assert code_plan(state)["payload"] == {"reason": reason}
    state = social_state()
    state["onboarding"]["selectedCharacterId"] = None
    assert code_plan(state)["payload"] == {"reason": "ONBOARDING_PENDING"}
    state = social_state()
    state["source"]["type"] = "FEED_COMMENT"
    state["localTime"] = "03:00:00"
    assert code_plan(state) is None


def test_wait_reason_is_a_bounded_code_and_social_actions_are_scoped():
    state = social_state()
    state["actions"] = list(ACTION_HELP)
    assert set(planning_actions(state)) == {"FEED_POST", "FEED_COMMENT", "FEED_LIKE", "WAIT"}
    def parse(reason):
        return parse_plan({"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps({
            "action": "WAIT", "payload": {"reason": reason}, "memory": ""})}]}, ["WAIT"])
    assert parse("NO_RELEVANT_POST")["payload"] == {"reason": "NO_RELEVANT_POST"}
    with pytest.raises(ValueError): parse("dump private source data")


@pytest.mark.parametrize("action,limit", [("FEED_POST",100),("FEED_COMMENT",80),("FEED_UPDATE",100)])
def test_social_limits_reject_long_blank_or_wrong_type_without_truncating(action,limit):
    def parse(content):
        payload={"request":{"content":content}} if action=="FEED_UPDATE" else {"content":content}
        return parse_plan({"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":action,"payload":payload,"memory":""})}]},[action])
    content="가"*(limit-1)+"🌱"
    accepted=parse(content)["payload"]
    assert (accepted["request"] if action=="FEED_UPDATE" else accepted)["content"]==content
    for invalid in (content+"가", " ", None, 123, "첫 줄\n둘째 줄\n셋째 줄"):
        with pytest.raises(ValueError):
            parse(invalid)
    assert parse("딱 한 줄만 적을래")["action"]==action


@pytest.mark.parametrize("persona,voice", [
    ("restart-morning","가볍고 솔직한 반말 일기"),
    ("steady-read","차분하고 부드러운 해요체"),
    ("observe-note","가장 짧고 건조한"),
    (None,"담백한 짧은 해요체"),
])
def test_existing_db_persona_gets_current_voice_at_generation_time(persona,voice):
    def handler(request):
        body=json.loads(request.content)
        prompt=json.loads(body["messages"][0]["content"])
        assert voice in body["system"]
        assert "ONBOARD_GOALS" not in body["system"]
        assert prompt["state"]["perception"]=={"canSeeImages":False}
        assert "최대 100자" in body["system"] and "최대 80자" in body["system"]
        assert prompt["state"]["persona"]=="기존 DB에 저장된 페르소나와 관심사"
        assert prompt["state"]["social"]["candidates"][0]["postId"]==7
        return httpx.Response(200,json={"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":"FEED_COMMENT","payload":{"postId":7,"content":"제목만 적는 것도 괜찮겠어요"},"memory":""})}]})
    state=social_state()
    state.update(persona="기존 DB에 저장된 페르소나와 관심사",perception={"canSeeImages":True})
    if persona is None:
        state.pop("personaKey")
    else:
        state["personaKey"]=persona
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        result=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert result.status_code==200
        assert result.json()["payload"]=={"postId":7,"content":"제목만 적는 것도 괜찮겠어요"}


def test_provider_overlong_social_text_is_not_published_or_retried():
    calls=[]
    def handler(request):
        calls.append(request)
        return httpx.Response(200,json={"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":"FEED_POST","payload":{"boardType":"FREE","content":"긴"*101,"imageIds":[]},"memory":""})}]})
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        result=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":social_state()})
        assert result.status_code==502
        assert result.json()["code"]=="AI_INVALID_ACTION_RESULT"
        assert "긴" not in result.text
    assert len(calls)==1


def test_photo_only_feed_never_fabricates_a_comment_or_calls_provider():
    state=social_state()
    state["social"]["candidates"]=[{"postId":7,"content":"내 방 사진 🛋️","likedByMe":False}]
    state["actions"]=["FEED_COMMENT","FEED_LIKE","WAIT"]
    state["social"]["allowedActions"]=state["actions"]
    with TestClient(create_app(CONFIG,ai_users=active())) as client:
        result=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert result.status_code==200
        assert result.json()=={"action":"FEED_LIKE","payload":{"postId":7,"liked":True},"memory":""}
        state["social"]["candidates"][0]["likedByMe"]=True
        result=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert result.json()["payload"]=={"reason":"NO_RELEVANT_POST"}


def test_mixed_feed_cannot_publish_photo_guess_or_store_its_memory():
    state=social_state()
    state["social"]["candidates"].append({"postId":8,"content":"내 방"})
    def handler(request):
        return httpx.Response(200,json={"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":"FEED_COMMENT","payload":{"postId":8,"content":"방 사진 보니 식물 배치가 좋아요"},"memory":"사진의 식물을 기억함"})}]})
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        result=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert result.status_code==200
        assert result.json()=={"action":"WAIT","payload":{"reason":"NO_RELEVANT_POST"},"memory":""}


def test_human_text_question_on_photo_post_can_still_be_answered():
    state=social_state()
    state["source"]={"type":"FEED_COMMENT","post":{"id":8,"content":"내 방"},"comment":{"content":"요즘 어떤 책 읽어요?"}}
    assert "FEED_COMMENT" in planning_actions(state)
