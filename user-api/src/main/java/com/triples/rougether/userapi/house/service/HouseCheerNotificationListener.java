package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 응원 저장 커밋 후 알림 발송 - 도메인 사건은 이벤트로 알림 진입점을 호출하는 #108 설계의 첫 리스너.
// 알림은 best-effort: 어떤 실패도 이미 커밋된 응원 요청으로 전파되지 않아야 한다. 그래서
// - 작업은 알림 전용 executor 로 넘긴다: AFTER_COMMIT 동기 실행 중엔 원 커넥션이 반납 전이라
//   여기서 새 트랜잭션을 열면 요청당 커넥션 2개를 점유한다(동시 커밋 몰리면 풀 고갈).
// - 제출은 @Async 프록시가 아니라 본문에서 직접 한다: 프록시 제출은 try 진입 전에 일어나
//   executor 거부(TaskRejectedException)가 요청으로 전파된다.
// - 트랜잭션은 TransactionTemplate(REQUIRES_NEW): 실행 스레드가 무엇이든 항상 새 트랜잭션으로
//   저장하고(AFTER_COMMIT 참여로 인한 내역 유실 방지), 커밋까지 블록 안에서 끝나 try/catch 가 전 구간을 삼킨다.
@Slf4j
@Component
public class HouseCheerNotificationListener {

    private static final String TITLE = "응원이 도착했어요";

    private final NotificationService notificationService;
    private final TransactionTemplate requiresNewTransaction;
    private final Executor notificationExecutor;

    public HouseCheerNotificationListener(NotificationService notificationService,
                                          PlatformTransactionManager transactionManager,
                                          @Qualifier("notificationTaskExecutor") Executor notificationExecutor) {
        this.notificationService = notificationService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.notificationExecutor = notificationExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheerSent(HouseCheerSentEvent event) {
        try {
            notificationExecutor.execute(() -> sendNotification(event));
        } catch (Exception e) {
            log.warn("응원 알림 작업 제출 실패 - cheerId={}, targetUserId={}", event.cheerId(), event.targetUserId(), e);
        }
    }

    private void sendNotification(HouseCheerSentEvent event) {
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
