package com.triples.rougether.batch.reminder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReminderPushWriterTest {

    @Mock private UserDeviceTokenRepository userDeviceTokenRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private DailyIncompleteDigestRepository dailyIncompleteDigestRepository;
    @Mock private NotificationSettingRepository notificationSettingRepository;
    @Mock private FcmSender fcmSender;

    private ReminderPushWriter writer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
        when(clockProvider.getIfAvailable(any())).thenReturn(Clock.systemUTC());
        writer = new ReminderPushWriter(userDeviceTokenRepository, notificationRepository,
                dailyIncompleteDigestRepository, notificationSettingRepository, fcmSender, clockProvider);
    }

    private Notification notificationOf(Long id, Long userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        Notification notification = Notification.create(
                user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }

    @Test
    void 사용자가_알림함에서_지운_PENDING_은_FCM_발송_없이_BLOCKED_로_종결한다() {
        // 설정은 전부 ON(행 없음)이라 삭제만이 발송을 막는 조건임.
        when(notificationSettingRepository.findAllByUserIdIn(any())).thenReturn(List.of());
        Notification deleted = notificationOf(5L, 1L);
        deleted.softDelete(Instant.parse("2026-09-08T03:00:00Z"));

        writer.write(new Chunk<>(deleted));

        verify(notificationRepository).updatePushStatus(5L, PushStatus.BLOCKED);
        verify(fcmSender, never()).send(anyList(), anyString(), anyString());
        verify(fcmSender, never()).send(anyList(), anyString(), anyString(), any());
        verify(userDeviceTokenRepository, never()).findAllByUserId(any());
    }
}
