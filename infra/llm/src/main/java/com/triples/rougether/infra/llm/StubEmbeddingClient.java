package com.triples.rougether.infra.llm;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// llm.api-key 미설정 환경(로컬·CI)용 stub. isAvailable()=false 라 호출자는 임베딩 없이 동작(fail-open)하거나 보류한다.
// embed 는 가짜 벡터를 돌려주지 않고 실패시킨다 — 0 벡터가 유사도 계산에 섞이면 잘못된 판정이 조용히 나가기 때문.
@ConditionalOnExpression("!T(org.springframework.util.StringUtils).hasText('${llm.api-key:}')")
@Component
public class StubEmbeddingClient implements EmbeddingClient {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        throw new LlmException("[stub] Embedding 호출 불가 — llm.api-key 미설정 (isAvailable()을 먼저 확인)", false);
    }
}
