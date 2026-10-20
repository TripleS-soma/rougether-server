package com.triples.rougether.batch.reminder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmDelivery;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReminderPushWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

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
        when(clockProvider.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        writer = new ReminderPushWriter(userDeviceTokenRepository, notificationRepository,
                dailyIncompleteDigestRepository, notificationSettingRepository,
                new FcmDelivery(fcmSender), clockProvider);
    }

    private Notification notificationOf(Long id, Long userId) {
        return notificationOf(id, userId, NotificationType.ROUTINE_REMINDER);
    }

    private Notification notificationOf(Long id, Long userId, NotificationType type) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        Notification notification = Notification.create(user, type, "제목", "내용", null);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }

    private void registerToken(Notification notification, String token) {
        when(userDeviceTokenRepository.findAllByUserId(notification.getUser().getId()))
                .thenReturn(List.of(UserDeviceToken.register(
                        notification.getUser(), token, DevicePlatform.ANDROID, NOW)));
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

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"APP_INACTIVITY_REMINDER", "ROOM_COBWEB_APPEARED"})
    void 토큰이_없으면_화면이동_payload를_만들지_않고_FAILED로_종결한다(NotificationType type) {
        // 토큰 없는 경로는 notificationId.toString()에 접근하지 않아야 함.
        Notification notification = notificationOf(null, 1L, type);
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(List.of());

        writer.write(new Chunk<>(notification));

        verify(notificationRepository).updatePushStatus(null, PushStatus.FAILED);
        verifyNoInteractions(fcmSender, dailyIncompleteDigestRepository);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"APP_INACTIVITY_REMINDER", "ROOM_COBWEB_APPEARED"})
    void 화면이동_payload_생성_오류도_발송하지_않고_FAILED로_종결한다(NotificationType type) {
        Notification notification = notificationOf(null, 1L, type);
        registerToken(notification, "token");

        writer.write(new Chunk<>(notification));

        verify(notificationRepository).updatePushStatus(null, PushStatus.FAILED);
        verifyNoInteractions(fcmSender, dailyIncompleteDigestRepository);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class,
            names = {"APP_INACTIVITY_REMINDER", "ROOM_COBWEB_APPEARED"})
    void 방으로_이동하는_알림은_타입과_알림ID를_payload에_유지한다(NotificationType type) {
        Notification notification = notificationOf(5L, 1L, type);
        registerToken(notification, "token");
        Map<String, String> data = Map.of(
                "type", type.name(), "screen", "myRoom", "notificationId", "5");
        when(fcmSender.send(List.of("token"), "제목", "내용", data))
                .thenReturn(new FcmSendResult(1, List.of()));

        writer.write(new Chunk<>(notification));

        verify(fcmSender).send(List.of("token"), "제목", "내용", data);
        verify(notificationRepository).updatePushStatus(5L, PushStatus.SENT);
        verifyNoInteractions(dailyIncompleteDigestRepository);
    }

    @Test
    void 일부_기기만_성공해도_무효토큰을_사용자_범위로_삭제하고_digest_발송시각을_남긴다() {
        Notification notification = notificationOf(5L, 1L, NotificationType.DAILY_INCOMPLETE_DIGEST);
        when(userDeviceTokenRepository.findAllByUserId(1L)).thenReturn(List.of(
                UserDeviceToken.register(notification.getUser(), "valid", DevicePlatform.ANDROID, NOW),
                UserDeviceToken.register(notification.getUser(), "invalid", DevicePlatform.IOS, NOW)));
        when(fcmSender.send(List.of("valid", "invalid"), "제목", "내용"))
                .thenReturn(new FcmSendResult(1, List.of("invalid")));

        writer.write(new Chunk<>(notification));

        verify(userDeviceTokenRepository).deleteAllByTokenInAndUserId(List.of("invalid"), 1L);
        verify(notificationRepository).updatePushStatus(5L, PushStatus.SENT);
        verify(dailyIncompleteDigestRepository).updatePushStatusByNotificationId(5L, PushStatus.SENT, NOW);
    }

    @Test
    void 무효토큰_삭제_DB_오류는_발송실패로_삼키지_않고_전파한다() {
        Notification notification = notificationOf(5L, 1L, NotificationType.DAILY_INCOMPLETE_DIGEST);
        registerToken(notification, "invalid");
        when(fcmSender.send(List.of("invalid"), "제목", "내용"))
                .thenReturn(new FcmSendResult(0, List.of("invalid")));
        var dbFailure = new DataAccessResourceFailureException("토큰 삭제 실패");
        doThrow(dbFailure).when(userDeviceTokenRepository)
                .deleteAllByTokenInAndUserId(List.of("invalid"), 1L);

        assertThatThrownBy(() -> writer.write(new Chunk<>(notification))).isSameAs(dbFailure);

        verifyNoInteractions(notificationRepository, dailyIncompleteDigestRepository);
    }

    @Test
    void 발송상태_저장_DB_오류는_FAILED로_덮어쓰지_않고_전파한다() {
        Notification notification = notificationOf(5L, 1L, NotificationType.DAILY_INCOMPLETE_DIGEST);
        registerToken(notification, "token");
        when(fcmSender.send(List.of("token"), "제목", "내용"))
                .thenReturn(new FcmSendResult(1, List.of()));
        var dbFailure = new DataAccessResourceFailureException("발송 상태 저장 실패");
        doThrow(dbFailure).when(notificationRepository).updatePushStatus(5L, PushStatus.SENT);

        assertThatThrownBy(() -> writer.write(new Chunk<>(notification))).isSameAs(dbFailure);

        verify(notificationRepository, never()).updatePushStatus(5L, PushStatus.FAILED);
        verifyNoInteractions(dailyIncompleteDigestRepository);
    }
}
