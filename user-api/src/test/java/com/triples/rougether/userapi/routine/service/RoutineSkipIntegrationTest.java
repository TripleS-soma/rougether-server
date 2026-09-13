package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.StreakStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.room.service.RoomGrowthService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.error.RoutineLogErrorCode;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 발생분 건너뜀(mobile #189): 보상·스트릭 없는 SKIPPED row 하나로 그날 발생분을 숨기고, 해제·재완료가 정합해야 함
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineSkipIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired private UserCharacterRepository userCharacterRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private RoutineLogRepository routineLogRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private WalletHistoryRepository walletHistoryRepository;
    @Autowired private StreakRepository streakRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private RoutineLogService service;
    private User user;
    private Long userId;
    private Long routineId;

    @BeforeEach
    void setUp() {
        service = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository,
                new DailyRewardService(routineLogRepository, todoRepository),
                new TransactionTemplate(transactionManager),
                org.mockito.Mockito.mock(HouseMissionService.class),
                new WalletHistoryRecorder(walletHistoryRepository),
                new RoomGrowthService(personalRoomRepository, userRepository, characterRepository, userCharacterRepository));
        user = userRepository.save(User.signUp());
        userId = user.getId();
        routineId = persistRoutine(user).getId();
        persistWallet(user, 50);
        persistStreak(userId, 3, YESTERDAY);
    }

    @Test
    void 오늘_건너뜀은_보상과_스트릭_없이_SKIPPED_row만_남긴다() {
        RoutineLogResponse response = service.skip(userId, routineId, TODAY);

        assertThat(response.status()).isEqualTo(RoutineLogStatus.SKIPPED);
        assertThat(response.completedAt()).isNull();
        assertThat(response.rewardAmount()).isZero();
        assertThat(response.houseMissionContribution()).isNull();
        // 스트릭은 그대로 돌려줄 뿐 갱신하지 않음(마지막 성공일 어제 → 오늘 기준 3 유지)
        assertThat(response.streak().currentCount()).isEqualTo(3);
        assertThat(streakRepository.findByUserId(userId).orElseThrow().getLastSuccessDate()).isEqualTo(YESTERDAY);
        assertThat(walletBalance()).isEqualTo(50);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, TODAY))
                .singleElement().satisfies(log -> assertThat(log.getStatus()).isEqualTo(RoutineLogStatus.SKIPPED));
    }

    @Test
    void 같은_날짜_재요청은_새_row_없이_기존_기록을_돌려준다() {
        Long first = service.skip(userId, routineId, TOMORROW).id();
        Long second = service.skip(userId, routineId, TOMORROW).id();

        assertThat(second).isEqualTo(first);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, TOMORROW)).hasSize(1);
    }

    @Test
    void 지난_날짜는_건너뛸_수_없다() {
        assertThatThrownBy(() -> service.skip(userId, routineId, YESTERDAY))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(RoutineLogErrorCode.SKIP_DATE_NOT_ALLOWED));
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, YESTERDAY)).isEmpty();
    }

    @Test
    void 이미_완료한_날짜는_건너뛸_수_없다() {
        service.complete(userId, routineId, new RoutineLogCreateRequest(TODAY));

        assertThatThrownBy(() -> service.skip(userId, routineId, TODAY))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(RoutineLogErrorCode.ALREADY_COMPLETED));
    }

    @Test
    void 취소는_건너뜀_해제로_동작하고_미래_날짜도_허용하며_지갑과_스트릭을_건드리지_않는다() {
        service.skip(userId, routineId, TOMORROW);

        var streak = service.cancel(userId, routineId, TOMORROW);

        assertThat(streak.currentCount()).isEqualTo(3);
        assertThat(walletBalance()).isEqualTo(50);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, TOMORROW)).isEmpty();
    }

    @Test
    void 건너뛴_날짜를_다시_완료하면_SKIPPED_row를_지우고_일반_완료로_보상한다() {
        service.skip(userId, routineId, TODAY);

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(TODAY));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.rewardAmount()).isEqualTo(10);
        assertThat(walletBalance()).isEqualTo(60);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, TODAY))
                .singleElement().satisfies(log -> assertThat(log.getStatus()).isEqualTo(RoutineLogStatus.COMPLETED));
        // 오늘 첫 완료라 스트릭이 이어짐(어제 3 → 오늘 4)
        assertThat(response.streak().currentCount()).isEqualTo(4);
    }

    @Test
    void 건너뜀_뒤_버전이_분기돼도_새_버전_id로_해제할_수_있다() {
        service.skip(userId, routineId, TOMORROW);
        Routine oldVersion = routineRepository.findById(routineId).orElseThrow();
        Routine newVersion = routineRepository.save(oldVersion.copyAsNewVersion(
                null, "새 버전", null, null, null, null, null, null));
        oldVersion.softDelete(Instant.now());
        routineRepository.save(oldVersion);

        service.cancel(userId, newVersion.getId(), TOMORROW);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, TOMORROW)).isEmpty();
    }

    private Routine persistRoutine(User owner) {
        Routine routine = routineRepository.save(Routine.create(owner, null, "아침 운동",
                AuthType.CHECK, "DAILY", null, null, null, null));
        routine.assignOriginToSelf();
        return routine;
    }

    private void persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }

    private void persistStreak(Long ownerId, int currentCount, LocalDate lastSuccessDate) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        Streak streak = Streak.start(owner, lastSuccessDate);
        ReflectionTestUtils.setField(streak, "currentCount", currentCount);
        ReflectionTestUtils.setField(streak, "longestCount", currentCount);
        ReflectionTestUtils.setField(streak, "status", StreakStatus.ACTIVE);
        streakRepository.save(streak);
    }

    private int walletBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
