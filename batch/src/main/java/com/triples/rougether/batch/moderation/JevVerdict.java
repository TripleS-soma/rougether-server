package com.triples.rougether.batch.moderation;

import java.util.Map;

// Jev choice 질문 결과. probabilities 는 판정 항목(SPAM…NONE)별 확률, 검증을 통과한 값만 담김.
public record JevVerdict(String model, Map<String, Double> probabilities, int inputTokens) {

    public JevVerdict {
        probabilities = Map.copyOf(probabilities);
    }
}
