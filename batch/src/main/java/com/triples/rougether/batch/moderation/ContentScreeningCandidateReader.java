package com.triples.rougether.batch.moderation;

import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.domain.moderation.repository.ContentScreeningRepository;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 한 실행의 판정 대상. batch-size 안에서 다음 순서로 채움:
// 1) 처리 대기 신고가 있는 글·댓글(#479, 7일 창 무관, 오래된 신고부터) — 운영자가 볼 점수를 먼저 붙임
// 2) 최근 7일 새 글·댓글(#478) — 글을 먼저 채우고 남은 자리를 댓글로
// 같은 대상이 두 경로에 모두 걸리면 신고된 대상으로 한 번만 넣음.
@Component
@RequiredArgsConstructor
public class ContentScreeningCandidateReader {

    private final ContentScreeningRepository screenings;
    private final ContentReportRepository reports;
    private final ContentScreeningProperties properties;

    @Transactional(readOnly = true)
    public List<ScreeningTarget> read(Instant now) {
        Instant since = now.minus(properties.window());
        Map<String, ScreeningTarget> targets = new LinkedHashMap<>();
        fill(targets, reports::findReportedPostCandidates, true);
        fill(targets, reports::findReportedCommentCandidates, true);
        fill(targets, limit -> screenings.findPostCandidates(since, limit), false);
        fill(targets, limit -> screenings.findCommentCandidates(since, limit), false);
        return List.copyOf(targets.values());
    }

    private void fill(Map<String, ScreeningTarget> targets, IntFunction<List<ScreeningCandidate>> query,
                      boolean reported) {
        int remaining = properties.batchSize() - targets.size();
        if (remaining <= 0) {
            return;
        }
        // 이미 담긴 대상과 겹칠 수 있어 남은 자리 + 이미 담긴 수만큼 넉넉히 읽고, 상한은 put 에서 지킴
        for (ScreeningCandidate candidate : query.apply(remaining + targets.size())) {
            if (targets.size() >= properties.batchSize()) {
                return;
            }
            ScreeningTarget target = new ScreeningTarget(candidate, reported);
            targets.putIfAbsent(target.key(), target);
        }
    }

    // 직전 실행 간격 동안 창 밖으로 밀려나 판정되지 못한 건수.
    @Transactional(readOnly = true)
    public long countDroppedSince(Instant now, Duration interval) {
        Instant windowStart = now.minus(properties.window());
        return screenings.countUnscreenedCreatedBetween(windowStart.minus(interval), windowStart);
    }
}
