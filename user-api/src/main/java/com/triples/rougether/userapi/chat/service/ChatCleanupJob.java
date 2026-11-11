package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.domain.chat.repository.ChatImageRepository;
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
@ConditionalOnProperty(name = "chat.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ChatCleanupJob {
    private static final int PAGE_SIZE = 100;

    private final ChatCleanupTransactions cleanup;
    private final ChatImageRepository images;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${chat.cleanup.delay-ms:300000}", initialDelayString = "${chat.cleanup.delay-ms:300000}")
    public void run() {
        long after = 0;
        while (true) {
            var now = clock.instant();
            var page = images.findCleanupCandidates(after, now, ChatImageTransactions.startOfKstDay(now),
                    PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) return;
            for (Long id : page) {
                try {
                    cleanup.image(id);
                } catch (RuntimeException e) {
                    log.warn("채팅 사진 정리 실패 - imageId={}", id, e);
                }
            }
            after = page.getLast();
        }
    }
}
