package com.triples.rougether.infra.llm;

// LLM 채팅 완성 호출 추상화. ai.service.enabled이면 내부 AI 서버, 아니면 llm.api-key에 따라 직접 호출 또는 stub을 선택함.
public interface LlmClient {

    // 응답 본문(assistant message content)을 그대로 돌려준다. 호출 실패(HTTP 오류·타임아웃·빈 응답)는 LlmException.
    String complete(LlmChatRequest request);

    // 실제 모델을 호출하도록 구성됐는지(실시간 연결 상태 점검은 아님). stub은 false를 돌려주며, 결과를 영구 저장하는 호출자는
    // false 일 때 생성을 보류해 가짜 응답이 정본으로 남지 않게 한다(fail-closed).
    default boolean isAvailable() {
        return true;
    }
}
