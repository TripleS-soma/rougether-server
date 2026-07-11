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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCommandServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @InjectMocks private NotificationCommandService notificationCommandService;

    private Notification notification() {
        return Notification.create(mock(User.class), NotificationType.ROUTINE_REMINDER, "제목", "내용", null);
    }

    @Test
    void 본인_알림을_읽음_처리한다() {
        Notification notification = notification();
        when(notificationRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(notification));

        notificationCommandService.markRead(1L, 5L);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void 이미_읽은_알림을_다시_읽음_처리해도_멱등이다() {
        Notification notification = notification();
        notification.markRead();
        when(notificationRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(notification));

        notificationCommandService.markRead(1L, 5L);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void 타인_또는_없는_알림_읽음_시도는_404() {
        when(notificationRepository.findByIdAndUserId(5L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationCommandService.markRead(2L, 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    void 전체_읽음은_본인_기준_bulk_쿼리를_호출한다() {
        notificationCommandService.markAllRead(1L);

        verify(notificationRepository).markAllReadByUserId(1L);
    }
}
