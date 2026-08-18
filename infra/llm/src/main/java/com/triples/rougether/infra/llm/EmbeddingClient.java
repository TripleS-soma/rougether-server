package com.triples.rougether.infra.llm;

import java.util.List;

// 문자열 목록을 임베딩 벡터로 바꾸는 호출 추상화. 구현은 설정(llm.api-key)에 따라 실제 API 클라이언트 또는 stub이 선택된다.
public interface EmbeddingClient {

    // inputs 순서 그대로 벡터를 돌려준다(inputs.get(i) ↔ 결과.get(i)). 빈 목록이면 빈 목록.
    // 호출 실패(HTTP 오류·타임아웃·개수 불일치)는 LlmException. 빈 문자열을 넣지 않는 것은 호출자 책임.
    List<float[]> embed(List<String> inputs);

    // 실제 모델과 연결돼 있는지. stub(키 미설정)은 false 를 돌려주며, 호출자는 false 면 임베딩 없이 동작(fail-open)하거나 보류한다.
    default boolean isAvailable() {
        return true;
    }
}
