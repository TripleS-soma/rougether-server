package com.triples.rougether.userapi.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTarget;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationSetting;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.client.AppleRevokeClient;
import com.triples.rougether.userapi.auth.client.AppleTokenExchangeClient;
import com.triples.rougether.userapi.auth.client.AppleTokenVerifier;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.client.KakaoUnlinkClient;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.auth.service.AuthService;
import com.triples.rougether.userapi.auth.service.GeneratedRefreshToken;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 실제 MySQL(Testcontainers)에서 탈퇴 트랜잭션(soft delete + 토큰 전량 폐기 + 연동 삭제)과
// 커밋 후 best-effort provider revoke, 재가입(신규 계정) 흐름을 검증함. 외부 HTTP 클라이언트만 mock.
@SpringBootTest
class MemberWithdrawalIntegrationTest {

    @MockitoBean
    private KakaoApiClient kakaoApiClient;
    @MockitoBean
    private AppleTokenVerifier appleTokenVerifier;
    @MockitoBean
    private AppleTokenExchangeClient appleTokenExchangeClient;
    @MockitoBean
    private KakaoUnlinkClient kakaoUnlinkClient;
    @MockitoBean
    private AppleRevokeClient appleRevokeClient;
    @MockitoBean
    private AssetStorageService assetStorageService;

    @Autowired
    private MemberWithdrawalService memberWithdrawalService;
    @Autowired
    private AuthService authService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OauthAccountRepository oauthAccountRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private DailyIncompleteDigestRepository dailyIncompleteDigestRepository;
    @Autowired
    private DailyIncompleteDigestTargetRepository dailyIncompleteDigestTargetRepository;
    @Autowired
    private NotificationSettingRepository notificationSettingRepository;
    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private UserGoalRepository userGoalRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private MemberService memberService;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private String kakaoLoginAs(String kakaoId) {
        String token = "tok-" + kakaoId;
        when(kakaoApiClient.fetchUser(token)).thenReturn(new KakaoUser(kakaoId, "a@b.com"));
        return token;
    }

    @Test
    void 탈퇴하면_soft_delete_되고_refresh_전량_폐기_연동_삭제_카카오_unlink_까지_수행된다() {
        String kakaoId = "kakao-" + UUID.randomUUID();
        String token = kakaoLoginAs(kakaoId);
        LoginResponse first = authService.kakaoLogin(token);
        authService.kakaoLogin(token); // 두 기기 로그인 상황: active refresh token 2개
        Long userId = first.userId();
        // 리마인더 push 대상이 되는 FCM 토큰 등록 상황 재현.
        userDeviceTokenRepository.save(UserDeviceToken.register(
                userRepository.findById(userId).orElseThrow(),
                "fcm-" + UUID.randomUUID(), DevicePlatform.ANDROID, java.time.Instant.now()));

        memberWithdrawalService.withdraw(userId);

        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)).isEmpty();
        assertThat(oauthAccountRepository.findAllByUser(user)).isEmpty();
        // 루틴은 soft delete 하지 않으므로, push 차단은 FCM 토큰 삭제가 담당함.
        assertThat(userDeviceTokenRepository.findAllByUserId(userId)).isEmpty();
        // 커밋 후 카카오 회원번호로 unlink 호출됨.
        verify(kakaoUnlinkClient).unlink(kakaoId);
    }

    @Test
    void provider_revoke_가_실패해도_탈퇴는_롤백되지_않고_유지된다() {
        String kakaoId = "kakao-" + UUID.randomUUID();
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs(kakaoId));
        doThrow(new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE))
                .when(kakaoUnlinkClient).unlink(anyString());

        memberWithdrawalService.withdraw(login.userId());

        User user = userRepository.findById(login.userId()).orElseThrow();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(login.userId())).isEmpty();
        assertThat(oauthAccountRepository.findAllByUser(user)).isEmpty();
    }

    @Test
    void 애플_연동은_저장된_refresh_token_을_복호화해_revoke_한다() {
        String appleId = "apple-" + UUID.randomUUID();
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, null));
        when(appleTokenExchangeClient.exchangeRefreshToken("authcode")).thenReturn("apple-rt-original");
        LoginResponse login = authService.appleLogin("idtok", "authcode");

        memberWithdrawalService.withdraw(login.userId());

        // 암호화 저장된 토큰이 원문으로 복호화되어 revoke에 사용됨(암복호화 왕복 검증 포함).
        verify(appleRevokeClient).revoke("apple-rt-original");
    }

    @Test
    void 탈퇴하면_개인정보가_즉시_익명화되고_프로필_원본은_커밋_후_삭제된다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        User user = userRepository.findById(login.userId()).orElseThrow();
        user.changeNickname("루티니");
        user.changeBio("소개글");
        user.changeProfileImage("profile/withdraw-test.png");
        userRepository.save(user);

        memberWithdrawalService.withdraw(login.userId());

        User withdrawn = userRepository.findById(login.userId()).orElseThrow();
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getNickname()).isNull();
        assertThat(withdrawn.getBio()).isNull();
        assertThat(withdrawn.getProfileImageKey()).isNull();
        // 접속기록·탈퇴시각은 보존됨.
        assertThat(withdrawn.getLastAccessedAt()).isNotNull();
        assertThat(withdrawn.getDeletedAt()).isNotNull();
        // 익명화 전에 스냅샷한 key로 S3 원본이 커밋 후 삭제됨.
        verify(assetStorageService).delete("profile/withdraw-test.png");
    }

    @Test
    void 프로필_이미지가_없으면_S3_삭제를_호출하지_않는다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));

        memberWithdrawalService.withdraw(login.userId());

        verify(assetStorageService, never()).delete(anyString());
    }

    @Test
    void S3_원본_삭제가_실패해도_탈퇴와_익명화는_유지된다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        User user = userRepository.findById(login.userId()).orElseThrow();
        user.changeProfileImage("profile/fail-test.png");
        userRepository.save(user);
        doThrow(new RuntimeException("s3 down")).when(assetStorageService).delete(anyString());

        memberWithdrawalService.withdraw(login.userId());

        User withdrawn = userRepository.findById(login.userId()).orElseThrow();
        assertThat(withdrawn.getDeletedAt()).isNotNull();
        assertThat(withdrawn.getProfileImageKey()).isNull();
    }

    @Test
    void 탈퇴하면_루틴_투두_카테고리가_연쇄_soft_delete_되고_리마인더_후보에서_빠진다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        User user = userRepository.findById(login.userId()).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "운동", "#FFFFFF", null, 1, PrivacyScope.PRIVATE));
        LocalTime nineAm = LocalTime.of(9, 0);
        Routine routine = routineRepository.save(Routine.create(
                user, category, "아침 스트레칭", AuthType.CHECK, "DAILY", null,
                nineAm, LocalDate.now(), null));
        Todo todo = todoRepository.save(Todo.create(user, category, "청소", null, LocalDate.now(), null));
        // 탈퇴 전에 이미 삭제된 루틴의 원래 삭제 시각은 보존돼야 함. DB TIMESTAMP가 초 단위라 절삭해 비교함.
        Instant earlierDeletedAt = Instant.now().minusSeconds(3600)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Routine alreadyDeleted = Routine.create(
                user, category, "옛 루틴", AuthType.CHECK, "DAILY", null, null, LocalDate.now(), null);
        alreadyDeleted.softDelete(earlierDeletedAt);
        alreadyDeleted = routineRepository.save(alreadyDeleted);
        // 완료 이력·스트릭은 탈퇴 트랜잭션에서는 보존함(하드 삭제는 batch 모듈 purge 담당).
        // 이력 날짜는 어제로 둠 — 오늘 COMPLETED면 리마인더 후보 조건(당일 미완료)에서 미리 빠져버림.
        RoutineLog log = routineLogRepository.save(RoutineLog.complete(
                routine, LocalDate.now().minusDays(1), Instant.now(), CurrencyType.COIN, 10));
        Streak streak = streakRepository.save(Streak.start(user, LocalDate.now()));
        // 다른 회원의 개인 데이터는 영향받지 않아야 함(스코프 검증).
        LoginResponse otherLogin = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        User other = userRepository.findById(otherLogin.userId()).orElseThrow();
        Routine otherRoutine = routineRepository.save(Routine.create(
                other, null, "남의 루틴", AuthType.CHECK, "DAILY", null, nineAm, LocalDate.now(), null));

        // 탈퇴 전에는 리마인더 후보에 잡힘.
        assertThat(reminderCandidateIds(nineAm)).contains(routine.getId());

        memberWithdrawalService.withdraw(login.userId());

        Routine deletedRoutine = routineRepository.findById(routine.getId()).orElseThrow();
        assertThat(deletedRoutine.getDeletedAt()).isNotNull();
        // bulk UPDATE 는 auditing 을 우회하므로 updated_at 을 직접 세팅함 — deletedAt과 같은 시각.
        assertThat(deletedRoutine.getUpdatedAt()).isEqualTo(deletedRoutine.getDeletedAt());
        assertThat(todoRepository.findById(todo.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(routineRepository.findById(alreadyDeleted.getId()).orElseThrow().getDeletedAt())
                .isEqualTo(earlierDeletedAt);
        // 완료 이력·스트릭은 그대로 남음.
        assertThat(routineLogRepository.findById(log.getId())).isPresent();
        assertThat(streakRepository.findById(streak.getId())).isPresent();
        // 다른 회원의 루틴은 건드리지 않음.
        assertThat(routineRepository.findById(otherRoutine.getId()).orElseThrow().getDeletedAt()).isNull();
        // 루틴 soft delete로 리마인더 후보에서 자연 제외됨.
        assertThat(reminderCandidateIds(nineAm)).doesNotContain(routine.getId());
    }

    @Test
    void 탈퇴하면_알림_수신함과_알림_설정_온보딩_목표가_즉시_삭제된다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        User user = userRepository.findById(login.userId()).orElseThrow();
        Goal goal = goalRepository.save(newGoal("wd-" + UUID.randomUUID().toString().substring(0, 8)));
        notificationRepository.save(
                Notification.create(user, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));
        DailyIncompleteDigest digest = dailyIncompleteDigestRepository.save(
                DailyIncompleteDigest.create(user, LocalDate.now(), 1, 0));
        Notification digestNotification = notificationRepository.save(Notification.create(
                user, NotificationType.DAILY_INCOMPLETE_DIGEST, "제목", "내용", digest.getId()));
        digest.linkNotification(digestNotification);
        dailyIncompleteDigestRepository.save(digest);
        dailyIncompleteDigestTargetRepository.save(DailyIncompleteDigestTarget.routine(digest, 101L));
        notificationSettingRepository.save(
                NotificationSetting.create(user, NotificationSettingType.ALL, true));
        userGoalRepository.save(UserGoal.of(user, goal, true));
        // 다른 회원의 본인 전용 데이터는 영향받지 않아야 함(스코프 검증).
        LoginResponse otherLogin = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        User other = userRepository.findById(otherLogin.userId()).orElseThrow();
        notificationRepository.save(
                Notification.create(other, NotificationType.ROUTINE_REMINDER, "제목", "내용", null));
        DailyIncompleteDigest otherDigest = dailyIncompleteDigestRepository.save(
                DailyIncompleteDigest.create(other, LocalDate.now(), 1, 0));
        Notification otherDigestNotification = notificationRepository.save(Notification.create(
                other, NotificationType.DAILY_INCOMPLETE_DIGEST, "제목", "내용", otherDigest.getId()));
        otherDigest.linkNotification(otherDigestNotification);
        dailyIncompleteDigestRepository.save(otherDigest);
        dailyIncompleteDigestTargetRepository.save(DailyIncompleteDigestTarget.routine(otherDigest, 202L));
        notificationSettingRepository.save(
                NotificationSetting.create(other, NotificationSettingType.ALL, true));
        userGoalRepository.save(UserGoal.of(other, goal, true));

        memberWithdrawalService.withdraw(login.userId());

        assertThat(notificationRepository.findPageByCursor(
                login.userId(), null, org.springframework.data.domain.Pageable.unpaged())).isEmpty();
        assertThat(dailyIncompleteDigestRepository.findByUserIdAndDigestDate(login.userId(), LocalDate.now()))
                .isEmpty();
        assertThat(notificationSettingRepository.findAllByUserId(login.userId())).isEmpty();
        assertThat(userGoalRepository.findByUserId(login.userId())).isEmpty();
        assertThat(notificationRepository.findPageByCursor(
                otherLogin.userId(), null, org.springframework.data.domain.Pageable.unpaged())).hasSize(2);
        assertThat(dailyIncompleteDigestRepository.findByUserIdAndDigestDate(otherLogin.userId(), LocalDate.now()))
                .isPresent();
        assertThat(dailyIncompleteDigestTargetRepository.findAllByDigestIdOrderByIdAsc(otherDigest.getId()))
                .hasSize(1);
        assertThat(notificationSettingRepository.findAllByUserId(otherLogin.userId())).hasSize(1);
        assertThat(userGoalRepository.findByUserId(otherLogin.userId())).hasSize(1);
    }

    // Goal 은 마스터 데이터라 공개 팩토리가 없음 — 온보딩 테스트와 동일하게 리플렉션으로 생성함.
    // 비활성으로 만들어 같은 DB 를 쓰는 온보딩 마스터 조회 테스트(활성 목록 검증)에 새지 않게 함.
    private Goal newGoal(String code) {
        Goal goal = org.springframework.beans.BeanUtils.instantiateClass(Goal.class);
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "code", code);
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "name", code + "-name");
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "sortOrder", 0);
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "active", false);
        return goal;
    }

    @Test
    void 탈퇴_후_잔여_access_token_으로는_개인정보를_재기입할_수_없다() {
        // 탈퇴 후 access token 만료 전(최대 30분) 재기입으로 익명화가 되돌려지지 않아야 함.
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        memberWithdrawalService.withdraw(login.userId());

        assertThatThrownBy(() -> memberService.getMe(login.userId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
        // 재기입 경로(닉네임·소개글 수정)도 같은 조회를 타고 401로 막힘.
        assertThatThrownBy(() -> memberService.updateMe(login.userId(),
                new com.triples.rougether.userapi.member.dto.MemberUpdateRequest("부활닉", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);

        User user = userRepository.findById(login.userId()).orElseThrow();
        assertThat(user.getNickname()).isNull();
    }

    private List<Long> reminderCandidateIds(LocalTime scheduledTime) {
        Instant now = Instant.now();
        return routineRepository.findReminderCandidates(
                        RoutineStatus.ACTIVE, scheduledTime, LocalDate.now(), RoutineLogStatus.COMPLETED,
                        NotificationType.ROUTINE_REMINDER, now.minusSeconds(3600), now.plusSeconds(3600),
                        0L, org.springframework.data.domain.Pageable.unpaged())
                .stream().map(Routine::getId).toList();
    }

    @Test
    void 탈퇴와_동시에_진행되던_로그인_flush_가_soft_delete_를_되돌리지_않는다() {
        // 로그인 트랜잭션(T2)이 탈퇴 커밋 전 스냅샷의 user(deletedAt=null)를 들고 있다가
        // 탈퇴(T1) 커밋 후 recordAccess()로 flush해도, @DynamicUpdate 덕에 deleted_at을 되쓰지 않아야 함.
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        Long userId = login.userId();

        transactionTemplate.executeWithoutResult(status -> {
            User stale = userRepository.findById(userId).orElseThrow();
            Thread withdrawThread = new Thread(() -> memberWithdrawalService.withdraw(userId));
            withdrawThread.start();
            try {
                withdrawThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            stale.recordAccess(Instant.now());
        });

        assertThat(userRepository.findById(userId).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void 이미_탈퇴한_회원의_재요청은_USER_NOT_FOUND_404_다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        memberWithdrawalService.withdraw(login.userId());

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(login.userId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 탈퇴한_소셜_계정으로_재로그인하면_새_계정으로_가입되고_옛_계정은_삭제_상태로_남는다() {
        String kakaoId = "kakao-" + UUID.randomUUID();
        String token = kakaoLoginAs(kakaoId);
        LoginResponse before = authService.kakaoLogin(token);
        memberWithdrawalService.withdraw(before.userId());

        LoginResponse after = authService.kakaoLogin(token);

        // 연동 삭제로 unique 가 풀려 신규 가입 플로우로 새 user 가 생성됨(재가입 허용).
        assertThat(after.isNewUser()).isTrue();
        assertThat(after.userId()).isNotEqualTo(before.userId());
        User oldUser = userRepository.findById(before.userId()).orElseThrow();
        assertThat(oldUser.getDeletedAt()).isNotNull();
        User newUser = userRepository.findById(after.userId()).orElseThrow();
        assertThat(newUser.getDeletedAt()).isNull();
        assertThat(oauthAccountRepository.findAllByUser(newUser)).hasSize(1);
    }

    @Test
    void 탈퇴_직후_살아남은_refresh_token_이_있어도_회전_시점에_거부된다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        memberWithdrawalService.withdraw(login.userId());

        // 탈퇴 트랜잭션의 전량 폐기 스냅샷과 동시에 회전돼 살아남은 토큰을 재현함.
        User user = userRepository.findById(login.userId()).orElseThrow();
        GeneratedRefreshToken survived = tokenService.generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(user, survived.tokenHash(), survived.expiresAt()));

        assertThatThrownBy(() -> authService.refresh(survived.raw()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }
}
