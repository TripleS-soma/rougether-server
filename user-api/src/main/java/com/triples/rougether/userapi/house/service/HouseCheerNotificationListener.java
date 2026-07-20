package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 응원 저장 커밋 후 알림 발송 - 도메인 사건은 이벤트로 알림 진입점을 호출하는 #108 설계의 첫 리스너.
//
// @Async: AFTER_COMMIT 동기 실행 중엔 원 트랜잭션 커넥션이 아직 반납 전이라, 여기서 새 트랜잭션을 열면
// 요청당 커넥션 2개를 점유해 동시 커밋이 몰리면 풀 고갈 위험이 있다. 알림 전용 executor 로 넘겨
// 요청 스레드가 즉시 커넥션을 반납하게 한다(알림은 best-effort 라 비동기로 충분).
//
// 트랜잭션 경계는 애노테이션이 아니라 TransactionTemplate(REQUIRES_NEW) 로 메서드 안에 둔다:
// - executor 포화로 caller-run 등 요청 스레드에서 실행돼도, AFTER_COMMIT 시점의 REQUIRED 참여
//   (이미 커밋된 트랜잭션 - 내역 유실)가 아니라 항상 새 트랜잭션으로 저장되게 하는 방어.
// - 메서드 @Transactional(REQUIRES_NEW) 는 커밋이 반환 후 프록시에서 일어나 커밋 예외를 안에서 못 잡는다.
//   template.execute 는 커밋까지 블록 안에서 끝나므로 바깥 try/catch 가 전 구간을 삼킨다.
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

    @Async("notificationTaskExecutor")
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
