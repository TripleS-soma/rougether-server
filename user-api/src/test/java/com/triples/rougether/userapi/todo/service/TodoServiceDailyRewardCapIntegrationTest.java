package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.todo.dto.TodoCompleteResponse;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TodoServiceDailyRewardCapIntegrationTest {

    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;

    private TodoService todoService;
    private Long userId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        todoService = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository, dailyRewardService);
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        persistWallet(user, 0);
    }

    @Test
    void 투두_4건_완료_후_5번째는_0_지급된다() {
        // 4개 투두 완료
        for (int i = 0; i < 4; i++) {
            Long todoId = todoService.create(userId,
                    new TodoCreateRequest("투두 " + i, null, null, null)).id();
            TodoCompleteResponse response = todoService.complete(userId, todoId);
            assertThat(response.rewardAmount()).isEqualTo(5);
        }
        assertThat(walletBalance()).isEqualTo(20);

        // 5번째 투두는 0 지급
        Long fifthTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 5", null, null, null)).id();
        TodoCompleteResponse fifthResponse = todoService.complete(userId, fifthTodoId);
        assertThat(fifthResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(20);
    }

    @Test
    void 상한_초과_완료_취소_후_재완료하면_다시_지급된다() {
        // 4건 완료
        Long[] todoIds = new Long[5];
        for (int i = 0; i < 4; i++) {
            todoIds[i] = todoService.create(userId,
                    new TodoCreateRequest("투두 " + i, null, null, null)).id();
            todoService.complete(userId, todoIds[i]);
        }
        assertThat(walletBalance()).isEqualTo(20);

        // 5번째: 0 지급
        todoIds[4] = todoService.create(userId,
                new TodoCreateRequest("투두 5", null, null, null)).id();
        TodoCompleteResponse fifthResponse = todoService.complete(userId, todoIds[4]);
        assertThat(fifthResponse.rewardAmount()).isEqualTo(0);

        // 4번째 취소
        todoService.cancelComplete(userId, todoIds[3]);
        assertThat(walletBalance()).isEqualTo(15);

        // 새 투두 완료하면 다시 지급
        Long newTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 new", null, null, null)).id();
        TodoCompleteResponse newResponse = todoService.complete(userId, newTodoId);
        assertThat(newResponse.rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(20);
    }

    @Test
    void 상한_초과_완료는_0_지급이므로_취소해도_지갑_불변() {
        // 4건 완료
        for (int i = 0; i < 4; i++) {
            Long todoId = todoService.create(userId,
                    new TodoCreateRequest("투두 " + i, null, null, null)).id();
            todoService.complete(userId, todoId);
        }
        assertThat(walletBalance()).isEqualTo(20);

        // 5번째: 0 지급
        Long fifthTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 5", null, null, null)).id();
        TodoCompleteResponse fifthResponse = todoService.complete(userId, fifthTodoId);
        assertThat(fifthResponse.rewardAmount()).isEqualTo(0);

        // 5번째 취소: reward_amount=0이므로 지갑 불변
        todoService.cancelComplete(userId, fifthTodoId);
        assertThat(walletBalance()).isEqualTo(20);
    }

    @Test
    void 지급된_완료_투두를_삭제해도_지급_슬롯이_복구되지_않는다() {
        // 4건 완료 (상한 소진)
        Long[] todoIds = new Long[4];
        for (int i = 0; i < 4; i++) {
            todoIds[i] = todoService.create(userId,
                    new TodoCreateRequest("투두 " + i, null, null, null)).id();
            todoService.complete(userId, todoIds[i]);
        }
        assertThat(walletBalance()).isEqualTo(20);

        // 지급된 완료 투두 삭제 — soft delete만 되고 코인은 회수되지 않음
        todoService.delete(userId, todoIds[3]);
        assertThat(walletBalance()).isEqualTo(20);

        // 새 투두 완료해도 슬롯이 복구되지 않아 0 지급
        Long newTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 new", null, null, null)).id();
        TodoCompleteResponse newResponse = todoService.complete(userId, newTodoId);
        assertThat(newResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(20);
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
