package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 응원 저장 커밋 후 알림 발송 - 도메인 사건은 이벤트로 알림 진입점을 호출하는 #108 설계의 첫 리스너.
//
// 트랜잭션 경계는 애노테이션이 아니라 TransactionTemplate(REQUIRES_NEW) 로 메서드 안에 둔다:
// - AFTER_COMMIT 시점엔 원 트랜잭션 리소스가 아직 바인딩돼 있어 REQUIRED 참여로는 내역이 커밋되지 않는다.
// - 메서드에 @Transactional(REQUIRES_NEW) 를 붙이면 커밋이 메서드 반환 후 프록시에서 일어나
//   커밋 예외(rollback-only 포함)를 안에서 못 잡고 이미 성공한 응원 요청까지 500 으로 만든다.
// - template.execute 는 커밋까지 블록 안에서 끝나므로 바깥 try/catch 가 전 구간을 삼킨다(알림은 best-effort).
@Slf4j
@Component
public class HouseCheerNotificationListener {

    private static final String TITLE = "응원이 도착했어요";

    private final NotificationService notificationService;
    private final TransactionTemplate requiresNewTransaction;

    public HouseCheerNotificationListener(NotificationService notificationService,
                                          PlatformTransactionManager transactionManager) {
        this.notificationService = notificationService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheerSent(HouseCheerSentEvent event) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> notificationService.send(
                    event.targetUserId(),
                    NotificationType.FRIEND_CHEER,
                    TITLE,
                    event.senderName() + "님: " + event.type().message(),
                    event.cheerId()));
        } catch (Exception e) {
            log.warn("응원 알림 발송 실패 - cheerId={}, targetUserId={}", event.cheerId(), event.targetUserId(), e);
        }
    }
}
