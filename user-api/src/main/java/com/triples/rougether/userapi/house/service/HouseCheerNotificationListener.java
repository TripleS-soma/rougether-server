package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 응원 저장 커밋 후 알림 발송 - 도메인 사건은 이벤트로 알림 진입점을 호출하는 #108 설계의 첫 리스너.
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseCheerNotificationListener {

    private static final String TITLE = "응원이 도착했어요";

    private final NotificationService notificationService;

    // AFTER_COMMIT 시점엔 원 트랜잭션 리소스가 아직 스레드에 바인딩돼 있어 REQUIRED 로 호출하면
    // 이미 커밋된 트랜잭션에 참여만 하게 된다 - 알림 내역이 커밋되지 않고, send() 내부의
    // 커밋후발송 이벤트도 발화하지 않는다. REQUIRES_NEW 로 경계를 분리해 내역 저장이 자체 커밋되게 한다.
    // 리스너가 예외를 던지면 이미 커밋된 요청까지 실패로 응답되므로 여기서 삼킨다(알림은 best-effort).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCheerSent(HouseCheerSentEvent event) {
        try {
            notificationService.send(
                    event.targetUserId(),
                    NotificationType.FRIEND_CHEER,
                    TITLE,
                    event.senderName() + "님: " + event.type().message(),
                    event.cheerId());
        } catch (Exception e) {
            log.warn("응원 알림 발송 실패 - cheerId={}, targetUserId={}", event.cheerId(), event.targetUserId(), e);
        }
    }
}
