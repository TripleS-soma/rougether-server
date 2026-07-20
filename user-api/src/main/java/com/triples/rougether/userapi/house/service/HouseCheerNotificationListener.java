package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 응원 저장 커밋 후 알림 발송 - 도메인 사건은 이벤트로 알림 진입점을 호출하는 #108 설계의 첫 리스너.
// NotificationService.send 는 자체 트랜잭션으로 내역을 저장하고 push 는 비동기(best-effort)다.
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseCheerNotificationListener {

    private static final String TITLE = "응원이 도착했어요";

    private final NotificationService notificationService;

    // AFTER_COMMIT 리스너가 예외를 던지면 이미 커밋된 요청까지 실패로 응답되므로 여기서 삼킨다(알림은 best-effort).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
