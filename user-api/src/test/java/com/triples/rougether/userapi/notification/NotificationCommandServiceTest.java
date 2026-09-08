package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.error.NotificationErrorCode;
import com.triples.rougether.userapi.notification.service.NotificationCommandService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T03:00:00Z");

    @Mock private NotificationRepository notificationRepository;
    private NotificationCommandService notificationCommandService;

    @BeforeEach
    void setUp() {
        notificationCommandService = new NotificationCommandService(
                notificationRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Notification notification() {
        return Notification.create(mock(User.class), NotificationType.ROUTINE_REMINDER, "제목", "내용", null);
    }

    private void givenOwned(Long notificationId, Long userId, Notification notification) {
        when(notificationRepository.findByIdAndUserId(notificationId, userId))
                .thenReturn(Optional.of(notification));
    }

    private void givenNotOwned(Long notificationId, Long userId) {
        when(notificationRepository.findByIdAndUserId(notificationId, userId))
                .thenReturn(Optional.empty());
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    void 본인_알림을_읽음_처리한다() {
        Notification notification = notification();
        givenOwned(5L, 1L, notification);

        notificationCommandService.markRead(1L, 5L);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void 이미_읽은_알림을_다시_읽음_처리해도_멱등이다() {
        Notification notification = notification();
        notification.markRead();
        givenOwned(5L, 1L, notification);

        notificationCommandService.markRead(1L, 5L);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void 타인_또는_없는_알림_읽음_시도는_404() {
        givenNotOwned(5L, 2L);

        assertNotFound(() -> notificationCommandService.markRead(2L, 5L));
    }

    @Test
    void 전체_읽음은_본인_기준_bulk_쿼리를_호출한다() {
        notificationCommandService.markAllRead(1L);

        verify(notificationRepository).markAllReadByUserId(1L);
    }

    @Test
    void 본인_알림을_삭제하면_현재_시각으로_deleted_at_이_찍힌다() {
        Notification notification = notification();
        givenOwned(5L, 1L, notification);

        notificationCommandService.delete(1L, 5L);

        assertThat(notification.isDeleted()).isTrue();
        assertThat(notification.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void 삭제된_알림도_본인_것이면_읽음_처리는_멱등_204_다() {
        Notification notification = notification();
        notification.softDelete(NOW.minusSeconds(60));
        givenOwned(5L, 1L, notification);

        notificationCommandService.markRead(1L, 5L);

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getDeletedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void 이미_삭제된_알림을_다시_삭제해도_멱등이며_최초_삭제_시각을_유지한다() {
        Instant first = NOW.minusSeconds(60);
        Notification notification = notification();
        notification.softDelete(first);
        givenOwned(5L, 1L, notification);

        notificationCommandService.delete(1L, 5L);

        assertThat(notification.getDeletedAt()).isEqualTo(first);
    }

    @Test
    void 타인_또는_없는_알림_삭제_시도는_404() {
        givenNotOwned(5L, 2L);

        assertNotFound(() -> notificationCommandService.delete(2L, 5L));
    }

    @Test
    void 전체_삭제는_본인_기준_현재_시각으로_bulk_soft_delete_를_호출한다() {
        notificationCommandService.deleteAll(1L);

        verify(notificationRepository).softDeleteAllByUserId(1L, NOW);
    }
}
