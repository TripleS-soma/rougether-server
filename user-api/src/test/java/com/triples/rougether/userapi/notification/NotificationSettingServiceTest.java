package com.triples.rougether.userapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.userapi.notification.dto.NotificationSettingResponse;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 알림 설정 의미 검증 - 행 없음=ON 기본값, 부분 변경 upsert, 마스터/그룹 게이트를 실제 DB로 확인.
@SpringBootTest
@Transactional
class NotificationSettingServiceTest {

    @Autowired private NotificationSettingService notificationSettingService;
    @Autowired private NotificationSettingRepository notificationSettingRepository;
    @Autowired private UserRepository userRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.signUp()).getId();
    }

    @Test
    void 설정_행이_없으면_전부_켜짐으로_조회된다() {
        assertThat(notificationSettingRepository.findAllByUserId(userId)).isEmpty();

        NotificationSettingResponse response = notificationSettingService.getSettings(userId);

        assertThat(response).isEqualTo(new NotificationSettingResponse(true, true, true));
    }

    @Test
    void 보낸_필드만_바뀌고_나머지는_켜짐이_유지된다() {
        NotificationSettingResponse response = notificationSettingService.updateSettings(
                userId, new NotificationSettingUpdateRequest(null, null, false));

        assertThat(response).isEqualTo(new NotificationSettingResponse(true, true, false));
        assertThat(notificationSettingService.getSettings(userId))
                .isEqualTo(new NotificationSettingResponse(true, true, false));
        // off 로 바꾼 그룹만 행이 생김 - 나머지는 행 없음(=ON) 그대로.
        assertThat(notificationSettingRepository.findAllByUserId(userId))
                .singleElement()
                .satisfies(setting -> {
                    assertThat(setting.getType()).isEqualTo(NotificationSettingType.HOUSE);
                    assertThat(setting.isEnabled()).isFalse();
                });
    }

    @Test
    void 같은_그룹을_다시_켜면_행이_늘지_않고_갱신된다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(null, null, false));

        NotificationSettingResponse response = notificationSettingService.updateSettings(
                userId, new NotificationSettingUpdateRequest(null, null, true));

        assertThat(response).isEqualTo(new NotificationSettingResponse(true, true, true));
        assertThat(notificationSettingRepository.findAllByUserId(userId))
                .singleElement()
                .satisfies(setting -> assertThat(setting.isEnabled()).isTrue());
    }

    @Test
    void 그룹을_끄면_소속_타입만_차단되고_다른_그룹은_허용된다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(null, false, null));

        assertThat(notificationSettingService.isPushAllowed(userId, NotificationType.ROUTINE_REMINDER)).isFalse();
        assertThat(notificationSettingService.isPushAllowed(userId, NotificationType.TODO_REMINDER)).isFalse();
        assertThat(notificationSettingService.isPushAllowed(userId, NotificationType.HOUSE_KICK)).isTrue();
        assertThat(notificationSettingService.isPushAllowed(userId, NotificationType.FRIEND_CHEER)).isTrue();
    }

    @Test
    void 마스터를_끄면_그룹이_켜져있어도_전부_차단된다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(false, true, true));

        for (NotificationType type : NotificationType.values()) {
            assertThat(notificationSettingService.isPushAllowed(userId, type)).isFalse();
        }
    }

    @Test
    void 마스터를_다시_켜면_이전_그룹_설정이_그대로_살아난다() {
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(null, null, false));
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(false, null, null));

        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(true, null, null));

        assertThat(notificationSettingService.getSettings(userId))
                .isEqualTo(new NotificationSettingResponse(true, true, false));
        assertThat(notificationSettingService.isPushAllowed(userId, NotificationType.HOUSE_KICK)).isFalse();
        assertThat(notificationSettingService.isPushAllowed(userId, NotificationType.ROUTINE_REMINDER)).isTrue();
    }

    @Test
    void 설정은_사용자별로_격리된다() {
        Long otherUserId = userRepository.save(User.signUp()).getId();
        notificationSettingService.updateSettings(userId, new NotificationSettingUpdateRequest(false, false, false));

        assertThat(notificationSettingService.getSettings(otherUserId))
                .isEqualTo(new NotificationSettingResponse(true, true, true));
        assertThat(notificationSettingService.isPushAllowed(otherUserId, NotificationType.HOUSE_KICK)).isTrue();
    }
}
