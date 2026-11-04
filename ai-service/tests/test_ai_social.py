import json
from collections import Counter
from datetime import date, timedelta

import httpx
import pytest
from fastapi.testclient import TestClient
from rougether_ai.ai_user_policy import (
    ACTION_HELP, SOCIAL_VOICES, casualize_social_text, code_plan, decision_system, parse_plan, planning_actions, social_post_brief,
)
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
    ("restart-morning","솔직한 반말 혼잣말"),
    ("steady-read","조용한 음슴체"),
    ("observe-note","가장 짧고 건조한"),
    (None,"짧은 반말·음슴체"),
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
            "action":"FEED_COMMENT","payload":{"postId":7,"content":"제목만 적어도 될 듯"},"memory":""})}]})
    state=social_state()
    state.update(persona="기존 DB에 저장된 페르소나와 관심사",perception={"canSeeImages":True})
    if persona is None:
        state.pop("personaKey")
    else:
        state["personaKey"]=persona
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        result=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert result.status_code==200
        assert result.json()["payload"]=={"postId":7,"content":"제목만 적어도 될 듯"}


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


def reply_state():
    state=social_state()
    state["source"]={"type":"FEED_COMMENT","post":{"postId":7,"content":"독서 이야기"},
        "comment":{"id":13,"post_id":7,"author_id":42,"content":"몇 쪽부터 읽을 거예요?","reply_to_comment_id":10},
        "inReplyTo":{"id":10,"author_id":1,"content":"짧게 읽을까 해요"},
        "thread":[{"id":10,"author_id":1,"content":"짧게 읽을까 해요"},
                  {"id":13,"author_id":42,"content":"몇 쪽부터 읽을 거예요?"}]}
    return state


def test_human_reply_uses_thread_context_and_binds_target_when_provider_omits_it():
    state=reply_state()
    def handler(request):
        body=json.loads(request.content)
        prompt=json.loads(body["messages"][0]["content"])
        source=prompt["state"]["source"]
        assert source["inReplyTo"]["content"]=="짧게 읽을까 해요"
        assert len(source["thread"])==2
        assert "source.comment.id" in body["system"]
        assert "마무리 반응" in body["system"]
        return httpx.Response(200,json={"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":"FEED_COMMENT","payload":{"postId":7,"content":"한 쪽부터요"},"memory":"읽을 계획을 답함"})}]})
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        response=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert response.status_code==200
        assert response.json()["payload"]["replyToCommentId"]==13


@pytest.mark.parametrize("payload", [
    {"postId":7,"content":"답글","replyToCommentId":10},
    {"postId":8,"content":"답글","replyToCommentId":13},
    {"postId":7,"content":"답글","replyToCommentId":True},
    {"postId":7,"content":"답글","replyToCommentId":"13"},
])
def test_provider_cannot_change_reply_target_or_post(payload):
    def handler(request):
        return httpx.Response(200,json={"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":"FEED_COMMENT","payload":payload,"memory":""})}]})
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        response=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":reply_state()})
        assert response.status_code==502
        assert response.json()["code"]=="AI_INVALID_ACTION_RESULT"


def test_a_closing_human_reaction_can_end_without_another_comment():
    state=reply_state()
    state["source"]["comment"]["content"]="감사해요ㅋㅋ"
    def handler(request):
        return httpx.Response(200,json={"stop_reason":"end_turn","content":[{"type":"text","text":json.dumps({
            "action":"WAIT","payload":{"reason":"NO_ACTION_DUE"},"memory":""})}]})
    with TestClient(create_app(CONFIG,ai_users=active(handler))) as client:
        response=client.post("/internal/v1/ai-users/decide",headers=AUTH,json={"aiUserId":1,"state":state})
        assert response.status_code==200
        assert response.json()["action"]=="WAIT"


@pytest.mark.parametrize("day", ["2026-11-03", "2026-12-31", "2028-02-29"])
def test_daily_post_topic_opportunities_favor_everyday_chatter_across_personas(day):
    briefs = [social_post_brief({**social_state(), "date": day, "personaKey": persona})
              for persona in SOCIAL_VOICES]
    assert Counter(brief["category"] for brief in briefs) == {"EVERYDAY": 7, "HABIT": 2, "APP": 1}
    everyday = [brief["subject"] for brief in briefs if brief["category"] == "EVERYDAY"]
    assert len(set(everyday)) == 7


@pytest.mark.parametrize("persona", SOCIAL_VOICES)
def test_each_persona_gets_the_topic_mix_over_ten_dates_without_visit_time_bias(persona):
    start = date(2026, 12, 27)
    briefs = []
    for offset in range(10):
        state = {**social_state(), "personaKey": persona, "date": (start + timedelta(days=offset)).isoformat()}
        brief = social_post_brief(state)
        assert social_post_brief({**state, "localTime": "23:15:00", "jobId": 999}) == brief
        assert social_post_brief({**state, "actions": list(reversed(state["actions"]))}) == brief
        briefs.append(brief)
    assert Counter(brief["category"] for brief in briefs) == {"EVERYDAY": 7, "HABIT": 2, "APP": 1}


@pytest.mark.parametrize("changes", [
    {"date": None}, {"date": ""}, {"date": "2026-02-30"}, {"date": 20261103},
    {"personaKey": None}, {"personaKey": "a-new-persona"},
])
def test_older_or_unknown_social_context_defaults_to_an_everyday_topic(changes):
    assert social_post_brief({**social_state(), **changes})["category"] == "EVERYDAY"


@pytest.mark.parametrize("source", ["FEED_POST", "FEED_COMMENT", "CHAT", "SCHEDULE"])
def test_post_topic_brief_does_not_redirect_other_events(source):
    state = {**social_state(), "source": {"type": source}}
    assert social_post_brief(state) is None
    assert "새 글 글감:" not in decision_system(state)


def test_post_topic_brief_is_absent_when_new_posts_are_not_allowed():
    state = {**social_state(), "actions": ["FEED_COMMENT", "FEED_LIKE", "WAIT"]}
    assert social_post_brief(state) is None
    assert "새 글 글감:" not in decision_system(state)


@pytest.mark.parametrize("content", ["저녁 메뉴 추천 좀", "난 치킨", "주말에 뭐 하지\n영화나 볼까"])
def test_generation_receives_post_brief_and_accepts_short_everyday_posts_unchanged(content):
    state = social_state()
    calls = []
    def handler(request):
        body = json.loads(request.content)
        calls.append(body)
        brief_line = next(line for line in body["system"].splitlines() if line.startswith("새 글 글감: "))
        assert json.loads(brief_line.removeprefix("새 글 글감: ")) == social_post_brief(state)
        assert set(json.loads(body["messages"][0]["content"])["actionFormats"]) == {
            "FEED_POST", "FEED_COMMENT", "FEED_LIKE", "WAIT"}
        return httpx.Response(200, json={"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps({
            "action": "FEED_POST", "payload": {"boardType": "FREE", "content": content, "imageIds": [], "topic": "일상 선택"},
            "memory": ""})}]})
    with TestClient(create_app(CONFIG, ai_users=active(handler))) as client:
        response = client.post("/internal/v1/ai-users/decide", headers=AUTH, json={"aiUserId": 1, "state": state})
        assert response.status_code == 200
        assert response.json()["payload"]["content"] == content
    assert len(calls) == 1


@pytest.mark.parametrize("raw,expected", [
    ("오늘 아침 걷기는 아직이다. 점심 전에 나가봐야지.", "오늘 아침 걷기는 아직이다 점심 전에 나가봐야지"),
    ("주말에 할 거 없음.\n영화나 볼까.", "주말에 할 거 없음\n영화나 볼까"),
    ("음... 글쎄", "음... 글쎄"),
    ("평점 3.5면 볼 만함?", "평점 3.5면 볼 만함?"),
    ("ㅋㅋ.", "ㅋㅋ"),
])
def test_sentence_periods_are_removed_without_touching_ellipsis_or_numbers(raw, expected):
    assert casualize_social_text(raw) == expected


@pytest.mark.parametrize("action,payload,expected", [
    ("FEED_POST", {"boardType": "FREE", "content": "저녁 뭐 먹냐.", "imageIds": []}, "저녁 뭐 먹냐"),
    ("FEED_COMMENT", {"postId": 7, "content": "나도 보리차파."}, "나도 보리차파"),
])
def test_parsed_social_text_drops_trailing_periods(action, payload, expected):
    result = parse_plan({"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps(
        {"action": action, "payload": payload, "memory": ""}, ensure_ascii=False)}]}, [action])
    assert result["payload"]["content"] == expected
    assert payload["content"].endswith(".")


def test_feed_update_request_is_casualized_without_mutating_input():
    payload = {"postId": 3, "request": {"content": "제목 바꿈."}}
    result = parse_plan({"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps(
        {"action": "FEED_UPDATE", "payload": payload, "memory": ""}, ensure_ascii=False)}]}, ["FEED_UPDATE"])
    assert result["payload"] == {"postId": 3, "request": {"content": "제목 바꿈"}}


@pytest.mark.parametrize("persona", [*SOCIAL_VOICES, None])
def test_social_prompt_asks_for_community_casual_voice_for_every_persona(persona):
    state = social_state()
    state["personaKey"] = persona
    system = decision_system(state)
    assert "반말·음슴체" in system and "마침표(.)를 찍지 않는다" in system
    assert "해요체(~요" in system and "쓰지 않는다" in system
    assert "~노" in system  # 비하 어미 금지가 함께 전달됨
    assert "예시 문장이나 예시의 소재" in system  # 예시 복사 방지
    assert "가벼운 일상 경험과 취향" in system and "state에 있는 그대로만" in system
    for voice in SOCIAL_VOICES.values():
        assert "해요체" not in voice
