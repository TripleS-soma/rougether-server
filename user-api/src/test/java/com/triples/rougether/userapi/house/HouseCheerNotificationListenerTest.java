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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

// 알림 발송 실패(내역 저장·커밋 예외)가 이미 커밋된 응원 요청으로 전파되지 않는지 검증.
class HouseCheerNotificationListenerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private HouseCheerNotificationListener listener;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        listener = new HouseCheerNotificationListener(notificationService, transactionManager);
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
        // 커밋은 TransactionTemplate.execute 블록 안에서 일어나므로 커밋 예외도 리스너 try/catch 가 잡는다
        doThrow(new RuntimeException("커밋 실패")).when(transactionManager).commit(any());

        assertThatCode(() -> listener.onCheerSent(
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
