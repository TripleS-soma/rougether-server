package com.triples.rougether.userapi.logingift.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.logingift.entity.LoginGiftEvent;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.logingift.error.LoginGiftErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoginGiftAccessRecorder {
    private final LoginGiftCampaignCatalog catalog;
    private final LoginGiftCaptureService capture;
    private final LoginGiftGrantService grant;
    private final TokenService tokens;
    private final Clock kstClock;
    private final LoginGiftCaptureWindow window;
    private final Set<Recipient> completed = ConcurrentHashMap.newKeySet();

    public void record(Long userId) {
        Instant at = kstClock.instant();
        if (!window.accepts(at)) { completed.clear(); return; }
        record(userId, at, campaigns(at));
    }

    // refresh 결과는 서버가 막 발급한 토큰에서 주체를 확인함. 요청 body의 사용자 ID는 신뢰하지 않음.
    public void recordRefresh(String issuedAccessToken) {
        Instant at = kstClock.instant();
        if (!window.accepts(at)) { completed.clear(); return; }
        var events = campaigns(at);
        if (!events.isEmpty()) record(tokens.parseAccessToken(issuedAccessToken).id(), at, events);
    }

    private List<LoginGiftEvent> campaigns(Instant at) {
        try {
            var events = catalog.activeAt(at);
            if (events.isEmpty()) completed.clear();
            return events;
        } catch (RuntimeException e) {
            throw new BusinessException(LoginGiftErrorCode.LOGIN_GIFT_CAPTURE_UNAVAILABLE);
        }
    }

    private void record(Long userId, Instant at, List<LoginGiftEvent> events) {
        for (var event : events) {
            var recipient = new Recipient(event.getId(), userId);
            if (completed.contains(recipient)) continue;
            try {
                if (!capture.capture(event.getId(), userId, at)) continue;
            } catch (RuntimeException e) {
                log.error("접속 선물 참여 저장 실패. eventId={}, userId={}, eligibleAt={}", event.getId(), userId, at, e);
                throw new BusinessException(LoginGiftErrorCode.LOGIN_GIFT_CAPTURE_UNAVAILABLE);
            }
            try {
                // 프록시가 커밋까지 성공한 뒤에만 완료 캐시를 남김. DB의 UNIQUE와 지급 기록이 정본임.
                if (grant.grant(event.getId(), userId)) completed.add(recipient);
            } catch (RuntimeException e) {
                log.warn("접속 선물 지급 대기. eventId={}, userId={}", event.getId(), userId, e);
            }
        }
    }

    private record Recipient(Long eventId, Long userId) {}
}
