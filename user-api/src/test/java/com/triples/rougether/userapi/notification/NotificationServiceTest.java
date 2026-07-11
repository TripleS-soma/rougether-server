package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.notification.service.NotificationService.NotificationCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private FcmPushExecutor fcmPushExecutor;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private NotificationService notificationService;

    @Test
    void send_시_알림_내역을_저장하고_커밋후발송_이벤트를_발행한다() {
        User user = mock(User.class);
        when(userRepository.getReferenceById(1L)).thenReturn(user);

        notificationService.send(1L, NotificationType.HOUSE_KICK, "제목", "본문");

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notificationCaptor.capture());
        Notification saved = notificationCaptor.getValue();
        assertThat(saved.getType()).isEqualTo(NotificationType.HOUSE_KICK);
        assertThat(saved.getTitle()).isEqualTo("제목");
        assertThat(saved.getBody()).isEqualTo("본문");
        assertThat(saved.isRead()).isFalse();

        ArgumentCaptor<NotificationCreatedEvent> eventCaptor = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new NotificationCreatedEvent(1L, "제목", "본문"));

        // push는 커밋 이후 리스너(onNotificationCreated)에서만 나가야 함 — send() 안에서 바로 호출되면 안 됨.
        verify(fcmPushExecutor, never()).push(any(), any(), any());
    }

    @Test
    void 커밋후_이벤트를_받으면_push를_호출한다() {
        notificationService.onNotificationCreated(new NotificationCreatedEvent(1L, "제목", "본문"));

        verify(fcmPushExecutor).push(1L, "제목", "본문");
    }
}
