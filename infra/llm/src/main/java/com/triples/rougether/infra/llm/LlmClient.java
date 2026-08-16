package com.triples.rougether.infra.llm;

// LLM 채팅 완성 호출 추상화. 구현은 설정(llm.api-key)에 따라 실제 API 클라이언트 또는 stub이 선택된다.
public interface LlmClient {

    // 응답 본문(assistant message content)을 그대로 돌려준다. 호출 실패(HTTP 오류·타임아웃·빈 응답)는 LlmException.
    String complete(LlmChatRequest request);

    // 실제 모델과 연결돼 있는지. stub(키 미설정)은 false 를 돌려주며, 결과를 영구 저장하는 호출자(주간 회고 등)는
    // false 일 때 생성을 보류해 가짜 응답이 정본으로 남지 않게 한다(fail-closed).
    default boolean isAvailable() {
        return true;
    }
}
