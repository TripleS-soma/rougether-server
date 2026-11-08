package com.triples.rougether.adminapi.notification.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.adminapi.notification.campaign.dto.*;
import com.triples.rougether.adminapi.notification.campaign.error.NotificationCampaignException;
import com.triples.rougether.adminapi.notification.campaign.service.*;
import com.triples.rougether.domain.admin.entity.*;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Audience;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Status;
import com.triples.rougether.domain.notification.entity.*;
import com.triples.rougether.domain.notification.repository.*;
import com.triples.rougether.infra.fcm.FcmDelivery;
import com.triples.rougether.infra.fcm.FcmDeliveryResult;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

// 별도 H2와 가짜 FCM만 사용함. 실제 사용자·운영 DB·외부 푸시에는 연결하지 않음.
@SpringBootTest(properties = {
        "admin.notification-campaigns.dispatch-enabled=false",
        "firebase.credentials-path=",
        "spring.datasource.url=jdbc:h2:mem:notification-campaign-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class NotificationCampaignIntegrationTest {
    @Autowired NotificationCampaignCommandService command;
    @Autowired NotificationCampaignAudienceService audience;
    @Autowired NotificationCampaignQueryService query;
    @Autowired NotificationCampaignDispatcher dispatcher;
    @Autowired NotificationCampaignRecipientSender recipientSender;
    @Autowired UserRepository users;
    @Autowired AdminUserRepository admins;
    @Autowired NotificationSettingRepository settings;
    @Autowired UserDeviceTokenRepository tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepository notifications;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean FcmDelivery delivery;
    @MockitoBean S3Client s3Client;

    private AdminUser admin;
    private User first, second;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        admin = admins.save(new AdminUser("notice-" + UUID.randomUUID().toString().substring(0, 8),
                "test-only", AdminRole.ADMIN));
        first = saveUser(User.signUp("notice-a-" + UUID.randomUUID() + "@test.invalid"));
        second = saveUser(User.signUp("notice-b-" + UUID.randomUUID() + "@test.invalid"));
        when(delivery.deliver(anyList(), anyString(), anyString(), any()))
                .thenReturn(new FcmDeliveryResult(FcmDeliveryResult.Status.SENT, List.of(), null));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from notification_campaign_recipients where campaign_id in "
                + "(select id from notification_campaigns where created_by_admin_id = ?)", admin.getId());
        jdbc.update("delete from notification_campaigns where created_by_admin_id = ?", admin.getId());
        for (Long id : userIds) {
            jdbc.update("delete from notification where user_id = ?", id);
            jdbc.update("delete from notification_setting where user_id = ?", id);
            jdbc.update("delete from user_device_token where user_id = ?", id);
            jdbc.update("delete from users where id = ?", id);
        }
        jdbc.update("delete from admin_users where id = ?", admin.getId());
        userIds.clear();
    }

    @Test
    void 미리보기와초안조회는_알림이나푸시를만들지않는다() {
        var preview = audience.preview(Audience.SELECTED, List.of(first.getId()), false);
        var draft = create(false, List.of(first.getId()));
        query.list();
        query.get(draft.id());

        assertThat(preview.recipientCount()).isEqualTo(1);
        assertThat(preview.pushNotRequestedCount()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(Status.DRAFT);
        assertThat(notificationCount(first.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 기본은알림함만생성하며_푸시미요청은차단과구분된다() {
        var draft = create(false, List.of(first.getId(), second.getId()));
        start(draft);
        dispatcher.dispatchBatch();

        var result = query.get(draft.id());
        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(result.pushNotRequestedCount()).isEqualTo(2);
        assertThat(result.pushBlockedCount()).isZero();
        assertThat(result.pushSentCount()).isZero();
        assertThat(jdbc.queryForList("select type from notification where user_id = ?", String.class, first.getId()))
                .containsExactly("ADMIN_NOTICE");
        assertThat(jdbc.queryForObject("select ref_id from notification where user_id = ?", Long.class, first.getId()))
                .isNull();
        verifyNoInteractions(delivery);
    }

    @Test
    void 전체대상은봇_AI_탈퇴자를빼고_초안뒤가입자는추가하지않는다() {
        saveUser(User.bot("notice-" + UUID.randomUUID().toString().substring(0, 8), "봇", ""));
        saveUser(User.ai("AI", ""));
        User withdrawn = User.signUp("notice-withdrawn@test.invalid");
        withdrawn.softDelete(Instant.now());
        saveUser(withdrawn);
        var draft = command.create(new NotificationCampaignCreateRequest(UUID.randomUUID(), "제목", "내용",
                Audience.ALL, List.of(), false), admin.getUsername());
        assertThat(draft.recipientCount()).isEqualTo(2);
        User late = saveUser(User.signUp("notice-late@test.invalid"));
        start(draft);
        dispatcher.dispatchBatch();

        assertThat(query.get(draft.id()).createdCount()).isEqualTo(2);
        assertThat(notificationCount(late.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 선택대상의없는ID_봇_AI_탈퇴ID는명시적으로거절한다() {
        User bot = saveUser(User.bot("notice-bot", "봇", ""));
        User ai = saveUser(User.ai("AI", ""));
        User gone = User.signUp("gone@test.invalid");
        gone.softDelete(Instant.now());
        saveUser(gone);
        for (Long invalid : List.of(Long.MAX_VALUE, bot.getId(), ai.getId(), gone.getId())) {
            assertThatThrownBy(() -> create(false, List.of(first.getId(), invalid)))
                    .isInstanceOf(NotificationCampaignException.class)
                    .extracting("status").isEqualTo(400);
        }
        assertThatThrownBy(() -> audience.resolve(Audience.SELECTED, List.of()))
                .isInstanceOf(NotificationCampaignException.class);
        assertThatThrownBy(() -> audience.resolve(Audience.ALL, List.of(first.getId())))
                .isInstanceOf(NotificationCampaignException.class);
        assertThat(notificationCount(first.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 동일요청재시도와동시발송에도사용자별한개만생성된다() throws Exception {
        UUID requestId = UUID.randomUUID();
        var input = new NotificationCampaignCreateRequest(requestId, "제목", "내용", Audience.SELECTED,
                List.of(first.getId(), first.getId()), false);
        var draft = command.create(input, admin.getUsername());
        assertThat(command.create(input, admin.getUsername()).id()).isEqualTo(draft.id());
        assertThatThrownBy(() -> command.create(new NotificationCampaignCreateRequest(requestId, "다른제목", "내용",
                Audience.SELECTED, List.of(first.getId()), false), admin.getUsername()))
                .isInstanceOf(NotificationCampaignException.class);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> start(draft));
            var b = pool.submit(() -> start(draft));
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(dispatcher::dispatchBatch);
            var b = pool.submit(dispatcher::dispatchBatch);
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        dispatcher.dispatchBatch();
        start(draft);
        dispatcher.dispatchBatch();

        assertThat(notificationCount(first.getId())).isEqualTo(1);
        assertThat(query.get(draft.id()).createdCount()).isEqualTo(1);
        verifyNoInteractions(delivery);
    }

    @Test
    void 대상수확인과명시확인없이발송승인할수없다() {
        var draft = create(false, List.of(first.getId()));
        assertThatThrownBy(() -> command.send(draft.id(), new NotificationCampaignSendRequest(false, 1), admin.getUsername()))
                .isInstanceOf(NotificationCampaignException.class);
        assertThatThrownBy(() -> command.send(draft.id(), new NotificationCampaignSendRequest(true, 2), admin.getUsername()))
                .isInstanceOf(NotificationCampaignException.class)
                .extracting("status").isEqualTo(409);
        assertThat(query.get(draft.id()).status()).isEqualTo(Status.DRAFT);
        dispatcher.dispatchBatch();
        assertThat(notificationCount(first.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 푸시미리보기는설정차단_토큰없음_실제후보를구분하되발송하지않는다() {
        token(first, "test-first");
        settings.save(NotificationSetting.create(first, NotificationSettingType.ALL, false));
        token(second, "test-second");
        User noToken = saveUser(User.signUp("no-token@test.invalid"));

        var preview = audience.preview(Audience.SELECTED, List.of(first.getId(), second.getId(), noToken.getId()), true);

        assertThat(preview.recipientCount()).isEqualTo(3);
        assertThat(preview.blockedCount()).isEqualTo(1);
        assertThat(preview.pushAllowedCount()).isEqualTo(1);
        assertThat(preview.tokenlessCount()).isEqualTo(1);
        assertThat(preview.pushNotRequestedCount()).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 푸시선택시기존마스터설정을지키고_알림함은항상남긴다() {
        token(first, "test-blocked");
        token(second, "test-allowed");
        settings.save(NotificationSetting.create(first, NotificationSettingType.ALL, false));
        var draft = create(true, List.of(first.getId(), second.getId()));
        start(draft);
        dispatcher.dispatchBatch();

        var result = query.get(draft.id());
        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(result.pushBlockedCount()).isEqualTo(1);
        assertThat(result.pushSentCount()).isEqualTo(1);
        verify(delivery, times(1)).deliver(anyList(), eq("제목"), eq("내용"), any());
    }

    @Test
    void 푸시실패는내역을되돌리거나재발송하지않는다() {
        token(first, "test-failed");
        when(delivery.deliver(anyList(), anyString(), anyString(), any()))
                .thenReturn(new FcmDeliveryResult(FcmDeliveryResult.Status.SEND_FAILED, List.of(),
                        new IllegalStateException("fake failure")));
        var draft = create(true, List.of(first.getId()));
        start(draft);
        dispatcher.dispatchBatch();
        dispatcher.dispatchBatch();

        assertThat(notificationCount(first.getId())).isEqualTo(1);
        assertThat(query.get(draft.id()).createdCount()).isEqualTo(1);
        assertThat(query.get(draft.id()).pushFailedCount()).isEqualTo(1);
        verify(delivery, times(1)).deliver(anyList(), anyString(), anyString(), any());
    }

    @Test
    void 초안작성뒤탈퇴한사람은내역과푸시모두생성하지않는다() {
        var draft = create(true, List.of(first.getId()));
        jdbc.update("update users set deleted_at = CURRENT_TIMESTAMP where id = ?", first.getId());
        start(draft);
        dispatcher.dispatchBatch();

        assertThat(query.get(draft.id()).skippedCount()).isEqualTo(1);
        assertThat(query.get(draft.id()).status()).isEqualTo(Status.COMPLETED);
        assertThat(notificationCount(first.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 부분처리후다음실행은미처리대상만이어간다() {
        var draft = create(false, List.of(first.getId(), second.getId()));
        start(draft);
        Long recipientId = jdbc.queryForObject(
                "select min(id) from notification_campaign_recipients where campaign_id = ?", Long.class, draft.id());
        recipientSender.process(recipientId);
        assertThat(query.get(draft.id()).createdCount()).isEqualTo(1);
        dispatcher.dispatchBatch();

        assertThat(query.get(draft.id()).createdCount()).isEqualTo(2);
        assertThat(notificationCount(first.getId())).isEqualTo(1);
        assertThat(notificationCount(second.getId())).isEqualTo(1);
        verifyNoInteractions(delivery);
    }

    @Test
    void 탈퇴시알림하드삭제를허용하고_발송이력조회는null알림을안전하게처리한다() {
        var draft = create(false, List.of(first.getId()));
        start(draft);
        dispatcher.dispatchBatch();
        assertThat(query.get(draft.id()).createdCount()).isEqualTo(1);

        // MemberWithdrawalService가 사용하는 동일 repository hard delete 경로를 실행함.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notifications.deleteAllByUserId(first.getId()));

        assertThat(notificationCount(first.getId())).isZero();
        assertThat(jdbc.queryForObject("select notification_id from notification_campaign_recipients "
                + "where campaign_id = ?", Long.class, draft.id())).isNull();
        var history = query.get(draft.id());
        assertThat(history.status()).isEqualTo(Status.COMPLETED);
        assertThat(history.createdCount()).isEqualTo(1);
        assertThat(history.pushNotRequestedCount()).isZero();
        assertThat(history.pushPendingCount()).isZero();
        assertThat(query.list()).extracting(NotificationCampaignResponse::id).contains(draft.id());
        dispatcher.dispatchBatch();
        assertThat(notificationCount(first.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 초안작성자와실제발송승인자를구분하고_재요청은최초승인자를보존한다() {
        AdminUser approver = admins.save(new AdminUser("approver-" + UUID.randomUUID().toString().substring(0, 8),
                "test-only", AdminRole.SUPER_ADMIN));
        try {
            var draft = create(false, List.of(first.getId()));
            var sent = command.send(draft.id(), new NotificationCampaignSendRequest(true, 1), approver.getUsername());
            assertThat(sent.createdByUsername()).isEqualTo(admin.getUsername());
            assertThat(sent.approvedByUsername()).isEqualTo(approver.getUsername());
            assertThat(sent.approvedAt()).isNotNull();
            var persisted = query.get(draft.id());
            // 첫 응답의 나노초와 TIMESTAMP(6) 저장값은 다를 수 있음. 보존 기준은 실제 DB 값임.
            assertThat(persisted.approvedAt()).isNotNull();
            assertThat(Duration.between(sent.approvedAt(), persisted.approvedAt()).abs())
                    .isLessThanOrEqualTo(Duration.ofNanos(1000));
            var repeated = command.send(draft.id(), new NotificationCampaignSendRequest(true, 1), admin.getUsername());
            assertThat(repeated.approvedByUsername()).isEqualTo(approver.getUsername());
            assertThat(repeated.approvedAt()).isEqualTo(persisted.approvedAt());
            assertThat(notificationCount(first.getId())).isZero();
            verifyNoInteractions(delivery);
        } finally {
            admins.delete(approver);
        }
    }

    @Test
    void 동시초안생성재시도는동일한대상스냅샷한개만만든다() throws Exception {
        var request = new NotificationCampaignCreateRequest(UUID.randomUUID(), "제목", "내용", Audience.SELECTED,
                List.of(first.getId()), false);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> command.create(request, admin.getUsername()));
            var b = pool.submit(() -> command.create(request, admin.getUsername()));
            Long firstCampaign = a.get(10, TimeUnit.SECONDS).id();
            Long secondCampaign = b.get(10, TimeUnit.SECONDS).id();
            assertThat(firstCampaign).isEqualTo(secondCampaign);
            assertThat(jdbc.queryForObject("select count(*) from notification_campaign_recipients where campaign_id = ?",
                    Integer.class, firstCampaign)).isEqualTo(1);
        }
        assertThat(notificationCount(first.getId())).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void 알림생성뒤트랜잭션롤백이면_알림과처리상태모두되돌리고_재실행은한번만지급한다() {
        token(first, "rollback-test-token");
        var draft = create(true, List.of(first.getId()));
        start(draft);
        Long recipientId = jdbc.queryForObject("select id from notification_campaign_recipients "
                + "where campaign_id = ?", Long.class, draft.id());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recipientSender.process(recipientId);
            throw new IllegalStateException("force-local-rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(notificationCount(first.getId())).isZero();
        assertThat(query.get(draft.id()).createdCount()).isZero();
        verifyNoInteractions(delivery);
        dispatcher.dispatchBatch();
        assertThat(notificationCount(first.getId())).isEqualTo(1);
        assertThat(query.get(draft.id()).createdCount()).isEqualTo(1);
        assertThat(query.get(draft.id()).pushSentCount()).isEqualTo(1);
        verify(delivery, times(1)).deliver(anyList(), anyString(), anyString(), any());
    }

    private NotificationCampaignResponse create(boolean push, List<Long> ids) {
        return command.create(new NotificationCampaignCreateRequest(UUID.randomUUID(), "제목", "내용",
                Audience.SELECTED, ids, push), admin.getUsername());
    }

    private void start(NotificationCampaignResponse draft) {
        command.send(draft.id(), new NotificationCampaignSendRequest(true, draft.recipientCount()), admin.getUsername());
    }

    private User saveUser(User user) {
        user = users.save(user);
        userIds.add(user.getId());
        return user;
    }

    private void token(User user, String token) {
        tokens.save(UserDeviceToken.register(user, token, DevicePlatform.ANDROID, Instant.now()));
    }

    private int notificationCount(Long userId) {
        return jdbc.queryForObject("select count(*) from notification where user_id = ?", Integer.class, userId);
    }
}
