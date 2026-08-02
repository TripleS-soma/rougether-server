package com.triples.rougether.userapi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryDirection;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryListResponse;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 재화 원장 기록·조회 통합테스트(#253) — 위험영역(재화) 정합성.
// 완료 시 기록, 취소 시 원 row 삭제, 지급 0 미기록, balance_after 스냅샷, 필터·페이징을 검증함.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class WalletHistoryRecordIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private WalletHistoryRepository walletHistoryRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RoutineLogService routineLogService;
    private TodoService todoService;
    private WalletQueryService walletQueryService;
    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        WalletHistoryRecorder recorder = new WalletHistoryRecorder(walletHistoryRepository);
        routineLogService = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository, dailyRewardService,
                new TransactionTemplate(transactionManager),
                org.mockito.Mockito.mock(HouseMissionService.class), recorder);
        todoService = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository, dailyRewardService, recorder);
        walletQueryService = new WalletQueryService(userWalletRepository, walletHistoryRepository);
        user = userRepository.save(User.signUp());
        userId = user.getId();
        persistWallet(user, 0);
    }

    @Test
    void 루틴_완료하면_ROUTINE_COMPLETE_이력이_잔액_스냅샷과_함께_남는다() {
        Long routineId = persistRoutine(user);

        routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));

        List<WalletHistory> histories = myHistories();
        assertThat(histories).hasSize(1);
        WalletHistory history = histories.get(0);
        assertThat(history.getReason()).isEqualTo(WalletHistoryReason.ROUTINE_COMPLETE);
        assertThat(history.getCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(history.getAmount()).isEqualTo(10);
        assertThat(history.getBalanceAfter()).isEqualTo(10);
        assertThat(history.getSourceType()).isEqualTo(WalletHistory.SOURCE_ROUTINE_LOG);
        assertThat(history.getSourceId()).isNotNull();
    }

    @Test
    void 루틴_완료를_취소하면_원_획득_이력이_삭제된다() {
        Long routineId = persistRoutine(user);
        routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        assertThat(myHistories()).hasSize(1);

        routineLogService.cancel(userId, routineId, TODAY);

        // 회수 row 를 남기지 않고 원 획득 row 만 삭제됨(#253 결정값)
        assertThat(myHistories()).isEmpty();
    }

    @Test
    void 투두_완료는_TODO_COMPLETE_이력을_남기고_취소하면_지운다() {
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("장보기", null, null, TODAY, null)).id();

        todoService.complete(userId, todoId);

        List<WalletHistory> histories = myHistories();
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getReason()).isEqualTo(WalletHistoryReason.TODO_COMPLETE);
        assertThat(histories.get(0).getAmount()).isEqualTo(10);
        assertThat(histories.get(0).getBalanceAfter()).isEqualTo(10);
        assertThat(histories.get(0).getSourceType()).isEqualTo(WalletHistory.SOURCE_TODO);
        assertThat(histories.get(0).getSourceId()).isEqualTo(todoId);

        todoService.cancelComplete(userId, todoId);

        assertThat(myHistories()).isEmpty();
    }

    @Test
    void 보상이_0인_과거_완료는_이력을_남기지_않는다() {
        Long routineId = persistRoutine(user);

        routineLogService.complete(userId, routineId,
                new RoutineLogCreateRequest(TODAY.minusDays(2)));

        assertThat(myHistories()).isEmpty();
    }

    @Test
    void 이력_조회는_재화와_방향_필터와_최신순_페이징을_지원한다() {
        // 적립 2건(코인·다이아) + 사용 1건(코인)을 직접 적재함 — 사용 경로(가챠)는 서비스 픽스처가 무거워 원장 row 로 대체
        walletHistoryRepository.save(WalletHistory.record(user, CurrencyType.COIN, 10,
                WalletHistoryReason.ROUTINE_COMPLETE, 10, WalletHistory.SOURCE_ROUTINE_LOG, 1L));
        walletHistoryRepository.save(WalletHistory.record(user, CurrencyType.COIN, -25,
                WalletHistoryReason.GACHA_DRAW, -15, WalletHistory.SOURCE_GACHA, 1L));
        walletHistoryRepository.save(WalletHistory.record(user, CurrencyType.DIAMOND, 3,
                WalletHistoryReason.GACHA_DUPLICATE_CONVERT, 3, WalletHistory.SOURCE_GACHA, 1L));

        Page<WalletHistory> all = walletHistoryRepository.findHistories(
                userId, null, null, PageRequest.of(0, 20));
        assertThat(all.getTotalElements()).isEqualTo(3);
        // 최신순(id desc)
        assertThat(all.getContent().get(0).getReason()).isEqualTo(WalletHistoryReason.GACHA_DUPLICATE_CONVERT);
        assertThat(all.getContent().get(2).getReason()).isEqualTo(WalletHistoryReason.ROUTINE_COMPLETE);

        Page<WalletHistory> coinOnly = walletHistoryRepository.findHistories(
                userId, CurrencyType.COIN, null, PageRequest.of(0, 20));
        assertThat(coinOnly.getTotalElements()).isEqualTo(2);

        Page<WalletHistory> earnOnly = walletHistoryRepository.findHistories(
                userId, null, true, PageRequest.of(0, 20));
        assertThat(earnOnly.getContent())
                .allSatisfy(history -> assertThat(history.getAmount()).isPositive())
                .hasSize(2);

        Page<WalletHistory> coinSpend = walletHistoryRepository.findHistories(
                userId, CurrencyType.COIN, false, PageRequest.of(0, 20));
        assertThat(coinSpend.getTotalElements()).isEqualTo(1);
        assertThat(coinSpend.getContent().get(0).getAmount()).isEqualTo(-25);

        // 다른 사용자의 이력은 보이지 않음(소유권)
        User other = userRepository.save(User.signUp());
        walletHistoryRepository.save(WalletHistory.record(other, CurrencyType.COIN, 10,
                WalletHistoryReason.ROUTINE_COMPLETE, 10, WalletHistory.SOURCE_ROUTINE_LOG, 2L));
        assertThat(walletHistoryRepository.findHistories(userId, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    void getHistories_응답은_페이지네이션_규약을_따른다() {
        walletHistoryRepository.save(WalletHistory.record(user, CurrencyType.COIN, 10,
                WalletHistoryReason.ROUTINE_COMPLETE, 10, WalletHistory.SOURCE_ROUTINE_LOG, 1L));
        walletHistoryRepository.save(WalletHistory.record(user, CurrencyType.COIN, -25,
                WalletHistoryReason.GACHA_DRAW, -15, WalletHistory.SOURCE_GACHA, 1L));

        WalletHistoryListResponse response = walletQueryService.getHistories(
                userId, CurrencyType.COIN, WalletHistoryDirection.SPEND, 0, 1);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).amount()).isEqualTo(-25);
        assertThat(response.items().get(0).reason()).isEqualTo(WalletHistoryReason.GACHA_DRAW);
        assertThat(response.items().get(0).balanceAfter()).isEqualTo(-15);
    }

    private List<WalletHistory> myHistories() {
        return walletHistoryRepository.findHistories(userId, null, null, PageRequest.of(0, 100))
                .getContent();
    }

    private Long persistRoutine(User owner) {
        Routine routine = Routine.create(owner, null, "아침 운동", AuthType.CHECK,
                null, null, null, null, null);
        return routineRepository.save(routine).getId();
    }

    private void persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }
}
