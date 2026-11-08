package com.triples.rougether.userapi.logingift.service;

import com.triples.rougether.domain.logingift.repository.LoginGiftParticipationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class LoginGiftRetryWorker {
    private final LoginGiftParticipationRepository participations;
    private final LoginGiftGrantService grant;
    @Value("${login-gift.retry.enabled:true}")
    private boolean enabled;
    private long afterId;

    @Scheduled(fixedDelayString = "${login-gift.retry.delay-ms:30000}", initialDelay = 60000)
    public void scheduledRetry() {
        if (enabled) retryPending();
    }

    // 이벤트 종료 뒤에도 접속 자격을 기준으로 복구함. 실패 행을 건너뛰어 다른 사용자 지급을 막지 않음.
    public synchronized void retryPending() {
        var pending = participations.findPending(afterId, PageRequest.of(0, 100));
        if (pending.isEmpty()) { afterId = 0; return; }
        for (var recipient : pending) {
            try {
                grant.grant(recipient.getEventId(), recipient.getUserId());
            } catch (RuntimeException e) {
                log.warn("접속 선물 복구 지급 실패. participationId={}", recipient.getId(), e);
            }
            afterId = recipient.getId();
        }
    }
}
