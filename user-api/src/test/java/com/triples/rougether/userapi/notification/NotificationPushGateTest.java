package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 알림 설정 off 는 push 만 막고 알림함 저장은 유지되는지를 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
// push 는 커밋 후(AFTER_COMMIT) 리스너에서 나가므로 테스트 트랜잭션 안에서는 애초에 발생하지 않음.
@SpringBootTest
class NotificationPushGateTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationSettingService notificationSettingService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FcmPushExecutor fcmPushExecutor;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.signUp()).getId();
    }

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM notification_setting WHERE user_id = ?", userId);
        userRepository.deleteById(userId);
    }

    private int savedNotificationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ?", Integer.class, userId);
    }

    private String savedPushStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT push_status FROM notification WHERE user_id = ?", String.class, userId);
    }

    @Test
    void 설정이_없으면_push가_나간다() {
        notificationService.send(userId, new NotificationContent(NotificationType.HOUSE_KICK, "제목", "본문"));

        verify(fcmPushExecutor).push(any(), eq(userId), eq("제목"), eq("본문"));
        assertThat(savedNotificationCount()).isEqualTo(1);
    }

    @Test
    void 그룹을_끄면_알림은_저장되고_push만_skip된다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(null, null, false));

        notificationService.send(userId, new NotificationContent(NotificationType.HOUSE_KICK, "제목", "본문"));

        verify(fcmPushExecutor, never()).push(any(), any(), any(), any());
        assertThat(savedNotificationCount()).isEqualTo(1);
        assertThat(savedPushStatus()).isEqualTo("BLOCKED");
    }

    // 그룹은 켜둔 채 마스터만 끈 경우. REMINDER 타입은 send() 를 타지 않아(batch 경로) 여기서 쓰지 않음 —
    // 마스터가 그룹과 무관하게 우선한다는 판정 자체는 NotificationSettingServiceTest 가 전 타입으로 검증함.
    @Test
    void 마스터를_끄면_그룹이_켜져있어도_저장되고_push만_skip된다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(false, null, true));

        notificationService.send(userId, new NotificationContent(NotificationType.HOUSE_MEMBER_JOINED, "제목", "본문"));

        verify(fcmPushExecutor, never()).push(any(), any(), any(), any());
        assertThat(savedNotificationCount()).isEqualTo(1);
        assertThat(savedPushStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void 끈_그룹이_아닌_알림은_그대로_push된다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(null, false, null));

        notificationService.send(userId, new NotificationContent(NotificationType.HOUSE_KICK, "제목", "본문"));

        verify(fcmPushExecutor).push(any(), eq(userId), eq("제목"), eq("본문"));
        assertThat(savedNotificationCount()).isEqualTo(1);
    }
}
