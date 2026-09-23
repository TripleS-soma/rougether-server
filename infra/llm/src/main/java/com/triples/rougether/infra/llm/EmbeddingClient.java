package com.triples.rougether.infra.llm;

import java.util.List;

// 문자열 목록을 임베딩 벡터로 바꾸는 추상화. ai.service.enabled이면 내부 AI 서버, 아니면 llm.api-key에 따라 직접 호출 또는 stub을 선택함.
public interface EmbeddingClient {

    // inputs 순서 그대로 벡터를 돌려준다(inputs.get(i) ↔ 결과.get(i)). 빈 목록이면 빈 목록.
    // 호출 실패(HTTP 오류·타임아웃·개수 불일치)는 LlmException. 빈 문자열을 넣지 않는 것은 호출자 책임.
    List<float[]> embed(List<String> inputs);

    // 실제 모델을 호출하도록 구성됐는지(실시간 연결 상태 점검은 아님). stub은 false를 돌려주며 호출자는 임베딩 없이 동작하거나 보류함.
    default boolean isAvailable() {
        return true;
    }
}
