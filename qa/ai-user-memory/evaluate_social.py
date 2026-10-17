"""합성 상태로만 SNS 자발적 선택을 검증함. 명시 실행 시 Claude를 최대 4회 호출함."""
import asyncio
import copy
import json
import os
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"ai-service"))
from rougether_ai.ai_user_models import DecisionRequest
from rougether_ai.ai_user_service import AiUserService,ClaudeGateway
from rougether_ai.ai_user_settings import AiUserSettings


def fixture(key,user,candidates,actions,preference):
    return {"aiUserId":user,"personaKey":key,"persona":preference,"profile":{"nickname":"합성 검증 캐릭터","bio":"작은 기록에 관심이 있어요"},
            "source":{"type":"SOCIAL_BROWSE"},"date":"2026-10-17","localTime":"20:30:00",
            "onboarding":{"goals":[1],"selectedCharacterId":1},"actions":actions,
            "social":{"allowedActions":actions,"candidates":copy.deepcopy(candidates),"recentActions":[],"firstPost":True,"preference":preference},
            "today":{"categories":[{"routines":[{"id":1,"title":"앱에 읽을 주제 메모하기","completed":True,"authType":"CHECK"}]}]},
            "recentOwnActivity":[{"action":"ROUTINE_CREATE","title":"앱에 읽을 주제 메모하기"}]}


async def run():
    settings=AiUserSettings("bolt://unused:7687","unused",os.environ["ANTHROPIC_API_KEY"])
    gateway=ClaudeGateway(settings)
    service=AiUserService(settings,None,gateway)
    candidates=[{"postId":101,"author":{"userId":900001,"nickname":"합성 기록 캐릭터"},
                 "content":"기록을 꾸준히 남기고 싶은데 항목이 너무 많아져요. 처음에는 어떤 기준으로 줄이면 좋을까요?",
                 "recentComments":[],"likedByMe":False}]
    choices=["FEED_POST","FEED_COMMENT","FEED_LIKE","WAIT"]
    fixtures=[
        fixture("social-cheer",227,candidates,choices,"사교형. 기록과 습관에 관심이 있고, 관련 기존 글에 구체적인 댓글로 먼저 참여함. 질문은 필요할 때만 함."),
        fixture("social-evening",228,candidates,choices,"사교형. 저녁에 작은 계획과 기록 주제를 나눔. 기존 글의 고민을 구체적으로 읽고 대화에 먼저 참여함."),
        fixture("steady-read",229,[],["FEED_POST","WAIT"],"독서와 기록을 좋아함. 첫 SNS 방문이며 앱에 만든 작은 계획이나 관심사를 소개할 수 있음."),
        fixture("observe-note",230,candidates,["FEED_COMMENT","FEED_LIKE","WAIT"],"관찰형. 읽는 비중이 높고 관심 있는 글에 가끔 좋아요나 짧은 댓글을 남김.")]
    results=[]
    try:
        for state in fixtures:
            if len(results) in (1,3) and results[0]["plan"]["action"]=="FEED_COMMENT":
                state["social"]["candidates"][0]["recentComments"]=[{"commentId":201,"authorUserId":227,
                    "content":results[0]["plan"]["payload"]["content"]}]
            plan=await service.decide(DecisionRequest(aiUserId=state["aiUserId"],state=state))
            if plan["action"] in ("FEED_COMMENT","FEED_LIKE"):
                assert plan["payload"]["postId"]==101,"unobserved post target"
            results.append({"persona":state["personaKey"],"plan":plan})
    finally:
        await gateway.close()
    assert any(row["plan"]["action"]=="FEED_COMMENT" for row in results[:2]),"sociable visits never comment"
    assert results[2]["plan"]["action"]=="FEED_POST","first introduction remained passive"
    return {"synthetic_only":True,"provider_calls":len(results),"production_writes":0,"results":results}


if __name__=="__main__":
    result=asyncio.run(run())
    output=Path(sys.argv[1]) if len(sys.argv)>1 else None
    if output: output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({"passed":True,"calls":result["provider_calls"],"actions":[r["plan"]["action"] for r in result["results"]]}))
