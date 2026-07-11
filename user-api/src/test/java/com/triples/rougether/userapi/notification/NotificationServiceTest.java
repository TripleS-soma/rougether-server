package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private FcmPushExecutor fcmPushExecutor;
    @InjectMocks private NotificationService notificationService;

    @Test
    void send_시_알림_내역을_저장하고_push를_호출한다() {
        User user = mock(User.class);
        when(userRepository.getReferenceById(1L)).thenReturn(user);

        notificationService.send(1L, NotificationType.HOUSE_KICK, "제목", "본문");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(NotificationType.HOUSE_KICK);
        assertThat(saved.getTitle()).isEqualTo("제목");
        assertThat(saved.getBody()).isEqualTo("본문");
        assertThat(saved.isRead()).isFalse();
        verify(fcmPushExecutor).push(1L, "제목", "본문");
    }

    @Test
    void push가_실패해도_이미_저장된_내역에는_영향이_없다() {
        User user = mock(User.class);
        when(userRepository.getReferenceById(1L)).thenReturn(user);
        doThrow(new RuntimeException("push 실패")).when(fcmPushExecutor).push(anyLong(), any(), any());

        // 실제 배포에선 push가 @Async라 별도 스레드에서 실패하고 호출자에게 전파되지 않지만,
        // 이 단위테스트는 save()가 push() 호출보다 먼저·무조건 실행됨을 검증해 정합성 의도를 확인함.
        assertThatThrownBy(() -> notificationService.send(1L, NotificationType.HOUSE_KICK, "제목", "본문"))
                .isInstanceOf(RuntimeException.class);

        verify(notificationRepository).save(any(Notification.class));
    }
}
