package com.triples.rougether.adminapi.bugreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.adminapi.bugreport.service.BugReportAdminService;
import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.NotificationSetting;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

// 답장 알림의 커밋 후 push 흐름(#348)을 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
// 알림 내역은 답장과 같은 트랜잭션에서 저장되고, push 는 커밋 후 동기 발송이라 reply() 리턴 직후 상태가 확정된다.
@SpringBootTest
class BugReportReplyPushFlowTest {

    @Autowired BugReportAdminService bugReportAdminService;
    @Autowired BugReportRepository bugReportRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserDeviceTokenRepository userDeviceTokenRepository;
    @Autowired NotificationSettingRepository notificationSettingRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean FcmSender fcmSender;
    @MockitoBean S3Client s3Client;

    private Long userId;
    private Long reportId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.signUp("reply-push@rougether.dev"));
        userId = user.getId();
        reportId = bugReportRepository.save(BugReport.submit(user, "푸시 제보", "내용", null, null)).getId();
    }

    @AfterEach
    void cleanUp() {
        // 커밋된 데이터를 FK 역순으로 직접 정리. 어드민 계정(시드 admin)은 공용이라 남긴다.
        jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM bug_report_replies WHERE bug_report_id = ?", reportId);
        jdbcTemplate.update("DELETE FROM bug_reports WHERE id = ?", reportId);
        jdbcTemplate.update("DELETE FROM user_device_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM notification_setting WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 발송_성공이면_SENT_이고_무효_토큰은_정리된다() {
        registerToken("alive-token");
        registerToken("dead-token");
        when(fcmSender.send(anyList(), anyString(), anyString()))
                .thenReturn(new FcmSendResult(1, List.of("dead-token")));

        bugReportAdminService.reply(reportId, "답장이에요", null, "admin");

        assertThat(replyNotificationPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(userDeviceTokenRepository.findAllByUserId(userId))
                .extracting(UserDeviceToken::getToken)
                .containsExactly("alive-token");
    }

    @Test
    void 마스터_ALL_off_면_BLOCKED_이고_발송하지_않는다() {
        registerToken("alive-token");
        notificationSettingRepository.save(NotificationSetting.create(
                userRepository.findById(userId).orElseThrow(), NotificationSettingType.ALL, false));

        bugReportAdminService.reply(reportId, "답장이에요", null, "admin");

        assertThat(replyNotificationPushStatus()).isEqualTo(PushStatus.BLOCKED);
        verify(fcmSender, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    void REMINDER_off_는_SERVICE_그룹_답장_push_를_막지_않는다() {
        registerToken("alive-token");
        notificationSettingRepository.save(NotificationSetting.create(
                userRepository.findById(userId).orElseThrow(), NotificationSettingType.REMINDER, false));
        when(fcmSender.send(anyList(), anyString(), anyString()))
                .thenReturn(new FcmSendResult(1, List.of()));

        bugReportAdminService.reply(reportId, "답장이에요", null, "admin");

        assertThat(replyNotificationPushStatus()).isEqualTo(PushStatus.SENT);
    }

    @Test
    void 토큰이_없으면_FAILED_이고_발송_시도하지_않는다() {
        bugReportAdminService.reply(reportId, "답장이에요", null, "admin");

        assertThat(replyNotificationPushStatus()).isEqualTo(PushStatus.FAILED);
        verify(fcmSender, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    void 발송_예외여도_답장과_알림_내역은_남고_FAILED_로_기록된다() {
        registerToken("alive-token");
        when(fcmSender.send(anyList(), anyString(), anyString())).thenThrow(new RuntimeException("fcm down"));

        bugReportAdminService.reply(reportId, "답장이에요", null, "admin");

        assertThat(replyNotificationPushStatus()).isEqualTo(PushStatus.FAILED);
        Integer replyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bug_report_replies WHERE bug_report_id = ?", Integer.class, reportId);
        assertThat(replyCount).isEqualTo(1);
    }

    private void registerToken(String token) {
        userDeviceTokenRepository.save(UserDeviceToken.register(
                userRepository.findById(userId).orElseThrow(), token, DevicePlatform.ANDROID, Instant.now()));
    }

    // 커밋 후 검증이라 영속성 컨텍스트를 우회해 DB 값을 직접 읽는다. 알림 1건·타입·refId 계약도 함께 확인.
    private PushStatus replyNotificationPushStatus() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT push_status, type, ref_id FROM notification WHERE user_id = ?", userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("type")).isEqualTo(NotificationType.BUG_REPORT_REPLY.name());
        assertThat(((Number) rows.getFirst().get("ref_id")).longValue()).isEqualTo(reportId);
        return PushStatus.valueOf((String) rows.getFirst().get("push_status"));
    }
}
