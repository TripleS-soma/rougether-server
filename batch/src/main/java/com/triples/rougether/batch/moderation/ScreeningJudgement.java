package com.triples.rougether.batch.moderation;

import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

// 판정 결과 해석(#478, 순수 계산). 유해 확률 = 1 − P(NONE), 사유는 NONE 을 뺀 최고 확률 항목(동률이면 정의 순서 앞).
public record ScreeningJudgement(BigDecimal harmProbability, ContentReportReason topReason, boolean flagged) {

    public static ScreeningJudgement of(Map<String, Double> probabilities, double flagThreshold) {
        double none = probabilities.getOrDefault(ScreeningCriteria.NONE, 0.0);
        double harm = Math.min(1, Math.max(0, 1 - none));
        ContentReportReason top = null;
        double topValue = -1;
        for (String key : ScreeningCriteria.CRITERIA.keySet()) {
            if (ScreeningCriteria.NONE.equals(key)) {
                continue;
            }
            double value = probabilities.getOrDefault(key, 0.0);
            if (value > topValue) {
                topValue = value;
                top = ContentReportReason.valueOf(key);
            }
        }
        // 저장·표시하는 4자리 값으로 비교해야 "p=0.6000 인데 미신고" 같은 경계 불일치가 생기지 않음
        BigDecimal rounded = BigDecimal.valueOf(harm).setScale(4, RoundingMode.HALF_UP);
        return new ScreeningJudgement(rounded, top, rounded.compareTo(BigDecimal.valueOf(flagThreshold)) >= 0);
    }

    public String detail() {
        return "auto: " + topReason.name() + " p=" + harmProbability.toPlainString();
    }
}
