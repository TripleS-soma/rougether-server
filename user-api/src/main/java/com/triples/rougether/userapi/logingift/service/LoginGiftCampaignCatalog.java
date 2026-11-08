package com.triples.rougether.userapi.logingift.service;

import com.triples.rougether.domain.logingift.entity.LoginGiftEvent;
import com.triples.rougether.domain.logingift.repository.LoginGiftEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginGiftCampaignCatalog {
    private final LoginGiftEventRepository events;
    private List<LoginGiftEvent> snapshot = List.of();
    private long loadedAt;

    // 매 요청 DB 조회를 피하되 기간 판정은 항상 요청 시각으로 수행함. 자정은 캐시 TTL에 영향받지 않음.
    public synchronized List<LoginGiftEvent> activeAt(Instant at) {
        long now = System.nanoTime();
        if (loadedAt == 0 || now - loadedAt >= TimeUnit.SECONDS.toNanos(30)) {
            snapshot = List.copyOf(events.findByEnabledTrue());
            loadedAt = now;
        }
        return snapshot.stream().filter(event -> event.accepts(at)).toList();
    }
}
