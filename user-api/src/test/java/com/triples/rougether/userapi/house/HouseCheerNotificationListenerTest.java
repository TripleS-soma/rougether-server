package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.house.entity.CheerType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.service.HouseCheerNotificationListener;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

// 알림의 어떤 실패(제출 거부·내역 저장·커밋 예외)도 이미 커밋된 응원 요청으로 전파되지 않는지 검증.
// executor 로 동기 실행(Runnable::run)을 주입해 비동기 타이밍 없이 검증한다.
class HouseCheerNotificationListenerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private HouseCheerNotificationListener listener;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        listener = new HouseCheerNotificationListener(notificationService, transactionManager, Runnable::run);
    }

    @Test
    void 알림_내역_저장이_실패해도_예외를_전파하지_않는다() {
        doThrow(new RuntimeException("알림 저장 실패")).when(notificationService)
                .send(anyLong(), any(), any(), any(), anyLong());

        assertThatCode(() -> listener.onCheerSent(
                new HouseCheerSentEvent(31L, 8L, "진형", CheerType.SUPPORT)))
                .doesNotThrowAnyException();
    }

    @Test
    void 새_트랜잭션_커밋_실패도_예외를_전파하지_않는다() {
        // 커밋은 TransactionTemplate.execute 블록 안에서 일어나므로 커밋 예외도 try/catch 가 잡는다
        doThrow(new RuntimeException("커밋 실패")).when(transactionManager).commit(any());

        assertThatCode(() -> listener.onCheerSent(
                new HouseCheerSentEvent(31L, 8L, "진형", CheerType.SUPPORT)))
                .doesNotThrowAnyException();
    }

    @Test
    void executor_제출이_거부돼도_예외를_전파하지_않는다() {
        // @Async 프록시 제출과 달리 본문에서 직접 제출하므로 거부 예외도 리스너 try/catch 가 잡는다
        HouseCheerNotificationListener rejectingListener = new HouseCheerNotificationListener(
                notificationService, transactionManager,
                task -> { throw new RejectedExecutionException("큐 포화"); });

        assertThatCode(() -> rejectingListener.onCheerSent(
                new HouseCheerSentEvent(31L, 8L, "진형", CheerType.SUPPORT)))
                .doesNotThrowAnyException();
    }

    @Test
    void 성공하면_FRIEND_CHEER_알림을_문구와_함께_보낸다() {
        listener.onCheerSent(new HouseCheerSentEvent(31L, 8L, "진형", CheerType.BEST));

        verify(notificationService).send(
                8L, NotificationType.FRIEND_CHEER, "응원이 도착했어요", "진형님: 오늘도 최고!", 31L);
    }
}
