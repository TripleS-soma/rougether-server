package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
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
    void 루틴3_투두1_지급_후_다음완료는_0지급() {
        // 루틴 3건 완료 (30코인)
        for (int i = 0; i < 3; i++) {
            Long routineId = persistRoutine();
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        assertThat(walletBalance()).isEqualTo(30);

        // 투두 1건 완료 (5코인) — 합산 4건
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("투두", null, null, TODAY)).id();
        todoService.complete(userId, todoId);
        assertThat(walletBalance()).isEqualTo(35);

        // 다음 루틴 완료 — 0 지급 (상한 도달)
        Long fifthRoutineId = persistRoutine();
        var routineResponse = routineLogService.complete(userId, fifthRoutineId,
                new RoutineLogCreateRequest(null));
        assertThat(routineResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(35);

        // 다음 투두 완료도 0 지급
        Long fifthTodoId = todoService.create(userId,
                new TodoCreateRequest("투두2", null, null, TODAY)).id();
        var todoResponse = todoService.complete(userId, fifthTodoId);
        assertThat(todoResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(35);
    }

    @Test
    void 루틴_투두_혼합_4건_후_추가는_모두_0지급() {
        // 루틴 2 + 투두 2 지급
        for (int i = 0; i < 2; i++) {
            Long routineId = persistRoutine();
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        for (int i = 0; i < 2; i++) {
            Long todoId = todoService.create(userId,
                    new TodoCreateRequest("투두 " + i, null, null, TODAY)).id();
            todoService.complete(userId, todoId);
        }
        assertThat(walletBalance()).isEqualTo(30); // 10+10+5+5

        // 5번째부터 모두 0
        Long fifthRoutineId = persistRoutine();
        var fifth = routineLogService.complete(userId, fifthRoutineId,
                new RoutineLogCreateRequest(null));
        assertThat(fifth.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(30);

        Long fifthTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 5", null, null, TODAY)).id();
        var sixth = todoService.complete(userId, fifthTodoId);
        assertThat(sixth.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(30);
    }

    @Test
    void 하나_취소_후_새로운_완료는_다시_지급된다() {
        // 루틴 3 + 투두 1 (4건, 35코인)
        Long[] routines = new Long[3];
        for (int i = 0; i < 3; i++) {
            routines[i] = persistRoutine();
            routineLogService.complete(userId, routines[i], new RoutineLogCreateRequest(null));
        }
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("투두", null, null, TODAY)).id();
        todoService.complete(userId, todoId);
        assertThat(walletBalance()).isEqualTo(35);

        // 루틴 하나 취소 (10 회수, 25코인)
        routineLogService.cancel(userId, routines[0], TODAY);
        assertThat(walletBalance()).isEqualTo(25);

        // 새 루틴 완료 (10 지급, 35코인)
        Long newRoutineId = persistRoutine();
        var newResponse = routineLogService.complete(userId, newRoutineId,
                new RoutineLogCreateRequest(null));
        assertThat(newResponse.rewardAmount()).isEqualTo(10);
        assertThat(walletBalance()).isEqualTo(35);
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
