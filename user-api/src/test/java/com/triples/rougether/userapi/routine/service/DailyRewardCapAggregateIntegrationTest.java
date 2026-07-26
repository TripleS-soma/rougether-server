package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
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
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class DailyRewardCapAggregateIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

@Autowired
    private PlatformTransactionManager transactionManager;

    private RoutineLogService routineLogService;
    private TodoService todoService;
    private Long userId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        routineLogService = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository, dailyRewardService,
                new TransactionTemplate(transactionManager));
        todoService = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository, dailyRewardService);
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        persistWallet(user, 0);
    }

    @Test
    void 루틴4_투두1로_50코인을_소진하면_다음완료는_0지급() {
        // 루틴 4건 완료 (40코인)
        for (int i = 0; i < 4; i++) {
            Long routineId = persistRoutine();
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        assertThat(walletBalance()).isEqualTo(40);

        // 투두 1건 완료 (10코인) — 합산 50 소진
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("투두", null, null, TODAY, null)).id();
        todoService.complete(userId, todoId);
        assertThat(walletBalance()).isEqualTo(50);

        // 다음 루틴 완료 — 0 지급 (상한 도달)
        Long extraRoutineId = persistRoutine();
        var routineResponse = routineLogService.complete(userId, extraRoutineId,
                new RoutineLogCreateRequest(null));
        assertThat(routineResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);

        // 다음 투두 완료도 0 지급
        Long extraTodoId = todoService.create(userId,
                new TodoCreateRequest("투두2", null, null, TODAY, null)).id();
        var todoResponse = todoService.complete(userId, extraTodoId);
        assertThat(todoResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 루틴_투두_혼합으로_50코인_소진_후_추가는_모두_0지급() {
        // 루틴 3 + 투두 2 = 50코인
        for (int i = 0; i < 3; i++) {
            Long routineId = persistRoutine();
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        for (int i = 0; i < 2; i++) {
            Long todoId = todoService.create(userId,
                    new TodoCreateRequest("투두 " + i, null, null, TODAY, null)).id();
            todoService.complete(userId, todoId);
        }
        assertThat(walletBalance()).isEqualTo(50); // 10×3 + 10×2

        // 이후 완료는 종류 무관 모두 0
        Long extraRoutineId = persistRoutine();
        var extraRoutine = routineLogService.complete(userId, extraRoutineId,
                new RoutineLogCreateRequest(null));
        assertThat(extraRoutine.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);

        Long extraTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 extra", null, null, TODAY, null)).id();
        var extraTodo = todoService.complete(userId, extraTodoId);
        assertThat(extraTodo.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 하나_취소_후_새로운_완료는_다시_지급된다() {
        // 루틴 4 + 투두 1 = 50코인
        Long[] routines = new Long[4];
        for (int i = 0; i < 4; i++) {
            routines[i] = persistRoutine();
            routineLogService.complete(userId, routines[i], new RoutineLogCreateRequest(null));
        }
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("투두", null, null, TODAY, null)).id();
        todoService.complete(userId, todoId);
        assertThat(walletBalance()).isEqualTo(50);

        // 루틴 하나 취소 (10 회수, 40코인)
        routineLogService.cancel(userId, routines[0], TODAY);
        assertThat(walletBalance()).isEqualTo(40);

        // 새 루틴 완료 (10 지급, 50코인)
        Long newRoutineId = persistRoutine();
        var newResponse = routineLogService.complete(userId, newRoutineId,
                new RoutineLogCreateRequest(null));
        assertThat(newResponse.rewardAmount()).isEqualTo(10);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 루틴_투두_합산_잔여가_정가보다_적으면_부분_지급되고_지갑은_50을_넘지_않는다() {
        // 루틴 4건(40) + 이전 정책으로 5코인 지급된 투두 1건 = 45 소진
        for (int i = 0; i < 4; i++) {
            Long routineId = persistRoutine();
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        persistLegacyCompletedTodoRewardedWith(5);
        assertThat(walletBalance()).isEqualTo(40);

        // 투두 완료 — 잔여 5만 지급
        Long partialTodoId = todoService.create(userId,
                new TodoCreateRequest("부분 지급 투두", null, null, TODAY, null)).id();
        assertThat(todoService.complete(userId, partialTodoId).rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);

        // 루틴 완료도 이제 0 — 지급 합계는 45 + legacy 5 = 상한 50
        Long nextRoutineId = persistRoutine();
        assertThat(routineLogService.complete(userId, nextRoutineId,
                new RoutineLogCreateRequest(null)).rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(45);
    }

    // 지급액이 10의 배수가 아닌 이력(정책 변경 전 5코인 투두)을 흉내내 부분 지급 경계를 만듦.
    // 지갑에는 더하지 않음 — 이 테스트가 보려는 건 잔여 한도 계산이지 잔액 이력이 아님
    private void persistLegacyCompletedTodoRewardedWith(int rewardAmount) {
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("이전 정책 투두", null, null, TODAY, null)).id();
        Todo todo = todoRepository.findById(todoId).orElseThrow();
        todo.complete(CurrencyType.COIN, rewardAmount, Instant.now());
        todoRepository.saveAndFlush(todo);
    }

    private Long persistRoutine() {
        User user = userRepository.getReferenceById(userId);
        Routine routine = Routine.create(user, null, "루틴명", AuthType.CHECK,
                null, null, null, null, null);
        return routineRepository.save(routine).getId();
    }

    private void persistWallet(User user, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", user);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }

    private long walletBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
