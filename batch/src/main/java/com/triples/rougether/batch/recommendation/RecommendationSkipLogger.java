package com.triples.rougether.batch.recommendation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;

// fault-tolerant skip 은 조용히 넘어가므로 어떤 사용자가 왜 빠졌는지 warn 으로 남긴다.
// 빠진 사용자는 쿨다운·JobInstance 때문에 이번 주 자동 재시도 경로가 없다(다음 주 배치에서 조건 충족 시 재생성).
@Slf4j
class RecommendationSkipLogger implements SkipListener<Long, RecommendationProcessingResult> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("조정 추천 reader skip", t);
    }

    @Override
    public void onSkipInProcess(Long userId, Throwable t) {
        log.warn("조정 추천 process skip - userId={}", userId, t);
    }

    @Override
    public void onSkipInWrite(RecommendationProcessingResult result, Throwable t) {
        Long userId = result.recommendations().isEmpty()
                ? result.eligibility() == null ? null : result.eligibility().getAssignment().getUser().getId()
                : result.recommendations().getFirst().getUser().getId();
        log.warn("조정 추천 write skip - userId={}, count={}", userId, result.recommendations().size(), t);
    }
}
