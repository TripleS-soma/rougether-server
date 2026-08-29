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
        // unique 위반 등으로 write 가 skip 되면 그 사용자의 이번 주 추천·적격 기록은 자동 재시도 없이 유실된다
        // (JobInstance 는 COMPLETED, 쿨다운은 안 걸렸으므로 다음 주 배치에서 조건 충족 시 재생성).
        log.warn("조정 추천 write skip - userId={}, count={}",
                result.userId(), result.recommendations().size(), t);
    }
}
