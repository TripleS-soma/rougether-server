package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.domain.feed.repository.FeedImageRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "feed.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class FeedCleanupJob {
    private final FeedCleanupTransactions cleanup;
    private final FeedImageRepository images;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${feed.cleanup.delay-ms:300000}", initialDelayString = "${feed.cleanup.delay-ms:300000}")
    public void run() {
        cleanup.withdrawnContent();
        long after = 0;
        while (true) {
            var page = images.findCleanupCandidates(after, clock.instant(), PageRequest.of(0, 100));
            if (page.isEmpty()) return;
            for (Long id : page) {
                try { cleanup.image(id); }
                catch (RuntimeException e) { log.warn("피드 이미지 정리 실패 - imageId={}", id, e); }
            }
            after = page.getLast();
        }
    }
}
