package com.triples.rougether.batch.moderation;

import com.triples.rougether.domain.moderation.repository.ContentScreeningRepository;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 한 실행의 판정 후보(#478). 글을 먼저 채우고 남은 자리를 댓글로 채워 batch-size 를 넘지 않게 함.
@Component
@RequiredArgsConstructor
public class ContentScreeningCandidateReader {

    private final ContentScreeningRepository screenings;
    private final ContentScreeningProperties properties;

    @Transactional(readOnly = true)
    public List<ScreeningCandidate> read(Instant now) {
        Instant since = now.minus(properties.window());
        List<ScreeningCandidate> candidates = new ArrayList<>(screenings.findPostCandidates(since,
                properties.batchSize()));
        int remaining = properties.batchSize() - candidates.size();
        if (remaining > 0) {
            candidates.addAll(screenings.findCommentCandidates(since, remaining));
        }
        return List.copyOf(candidates);
    }

    // 직전 실행 간격 동안 창 밖으로 밀려나 판정되지 못한 건수.
    @Transactional(readOnly = true)
    public long countDroppedSince(Instant now, Duration interval) {
        Instant windowStart = now.minus(properties.window());
        return screenings.countUnscreenedCreatedBetween(windowStart.minus(interval), windowStart);
    }
}
