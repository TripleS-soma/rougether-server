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
