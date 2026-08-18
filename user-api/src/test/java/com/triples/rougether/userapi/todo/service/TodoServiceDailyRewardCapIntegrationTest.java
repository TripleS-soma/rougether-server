package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.todo.dto.TodoCompleteResponse;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TodoServiceDailyRewardCapIntegrationTest {

    // 코인은 dueDate가 오늘인 완료에만 지급되므로 픽스처는 오늘 마감으로 만듦
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

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
    private RoutineLogRepository routineLogRepository;

    private TodoService todoService;
    private Long userId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        todoService = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository, dailyRewardService,
                new WalletHistoryRecorder(walletHistoryRepository));
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        persistWallet(user, 0);
    }

    @Test
    void 투두_5건_완료로_50코인을_소진하면_6번째는_0_지급된다() {
        // 10코인 × 5건 = 상한 50 소진
        for (int i = 0; i < 5; i++) {
            Long todoId = completeNewTodo("투두 " + i);
            assertThat(todoRepository.findById(todoId).orElseThrow().getRewardAmount())
                    .isEqualTo(10);
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 6번째 투두는 0 지급
        Long sixthTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 6", null, null, TODAY, null, null, null)).id();
        TodoCompleteResponse sixthResponse = todoService.complete(userId, sixthTodoId);
        assertThat(sixthResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 상한_소진_완료_취소_후_재완료하면_다시_지급된다() {
        Long[] todoIds = new Long[6];
        for (int i = 0; i < 5; i++) {
            todoIds[i] = completeNewTodo("투두 " + i);
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 6번째: 0 지급
        todoIds[5] = todoService.create(userId,
                new TodoCreateRequest("투두 6", null, null, TODAY, null, null, null)).id();
        TodoCompleteResponse sixthResponse = todoService.complete(userId, todoIds[5]);
        assertThat(sixthResponse.rewardAmount()).isEqualTo(0);

        // 5번째 취소 → 한도 10 복구
        todoService.cancelComplete(userId, todoIds[4]);
        assertThat(walletBalance()).isEqualTo(40);

        // 새 투두 완료하면 다시 지급
        Long newTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 new", null, null, TODAY, null, null, null)).id();
        TodoCompleteResponse newResponse = todoService.complete(userId, newTodoId);
        assertThat(newResponse.rewardAmount()).isEqualTo(10);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 상한_소진_후_완료는_0_지급이므로_취소해도_지갑_불변() {
        for (int i = 0; i < 5; i++) {
            completeNewTodo("투두 " + i);
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 6번째: 0 지급
        Long sixthTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 6", null, null, TODAY, null, null, null)).id();
        TodoCompleteResponse sixthResponse = todoService.complete(userId, sixthTodoId);
        assertThat(sixthResponse.rewardAmount()).isEqualTo(0);

        // 6번째 취소: reward_amount=0이므로 지갑 불변
        todoService.cancelComplete(userId, sixthTodoId);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 지급된_완료_투두를_삭제해도_지급_한도가_복구되지_않는다() {
        Long[] todoIds = new Long[5];
        for (int i = 0; i < 5; i++) {
            todoIds[i] = completeNewTodo("투두 " + i);
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 지급된 완료 투두 삭제 — soft delete만 되고 코인은 회수되지 않음
        todoService.delete(userId, todoIds[4]);
        assertThat(walletBalance()).isEqualTo(50);

        // 새 투두 완료해도 한도가 복구되지 않아 0 지급
        Long newTodoId = todoService.create(userId,
                new TodoCreateRequest("투두 new", null, null, TODAY, null, null, null)).id();
        TodoCompleteResponse newResponse = todoService.complete(userId, newTodoId);
        assertThat(newResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 남은_한도가_정가보다_적으면_남은_만큼만_부분_지급된다() {
        // 45코인 소진 상태 — 10×4건 + 정책 변경 전 5코인으로 기록된 이력 1건
        for (int i = 0; i < 4; i++) {
            completeNewTodo("투두 " + i);
        }
        persistLegacyCompletedTodoRewardedWith(5);
        assertThat(walletBalance()).isEqualTo(40);

        // 잔여 5 < 정가 10 → 5만 지급
        Long partialTodoId = todoService.create(userId,
                new TodoCreateRequest("부분 지급 투두", null, null, TODAY, null, null, null)).id();
        TodoCompleteResponse partial = todoService.complete(userId, partialTodoId);
        assertThat(partial.rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);

        // 상한 소진 → 다음 완료는 0
        Long nextTodoId = todoService.create(userId,
                new TodoCreateRequest("다음 투두", null, null, TODAY, null, null, null)).id();
        assertThat(todoService.complete(userId, nextTodoId).rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(45);
    }

    @Test
    void 부분_지급_건을_취소하면_지급액만큼만_환불되고_한도도_그만큼만_복구된다() {
        for (int i = 0; i < 4; i++) {
            completeNewTodo("투두 " + i);
        }
        persistLegacyCompletedTodoRewardedWith(5);

        Long partialTodoId = todoService.create(userId,
                new TodoCreateRequest("부분 지급 투두", null, null, TODAY, null, null, null)).id();
        assertThat(todoService.complete(userId, partialTodoId).rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);

        // 취소: 정가 10이 아니라 실지급 5만 회수
        todoService.cancelComplete(userId, partialTodoId);
        assertThat(walletBalance()).isEqualTo(40);

        // 복구된 한도도 5뿐이므로 다음 완료 역시 5만 지급
        Long nextTodoId = todoService.create(userId,
                new TodoCreateRequest("다음 투두", null, null, TODAY, null, null, null)).id();
        assertThat(todoService.complete(userId, nextTodoId).rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);
    }

    @Test
    void 마감일이_지난_완료는_0_지급이고_상한_집계에도_잡히지_않는다() {
        Long pastTodoId = todoService.create(userId,
                new TodoCreateRequest("어제 마감", null, null, TODAY.minusDays(1), null, null, null)).id();
        assertThat(todoService.complete(userId, pastTodoId).rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(0);

        // 과거 마감 완료가 한도를 깎지 않았으므로 오늘 5건 모두 정가 지급
        for (int i = 0; i < 5; i++) {
            completeNewTodo("투두 " + i);
        }
        assertThat(walletBalance()).isEqualTo(50);
    }

    private Long completeNewTodo(String title) {
        Long todoId = todoService.create(userId,
                new TodoCreateRequest(title, null, null, TODAY, null, null, null)).id();
        todoService.complete(userId, todoId);
        return todoId;
    }

    // 지급액이 10의 배수가 아닌 이력(정책 변경 전 5코인 투두)을 흉내내 부분 지급 경계를 만듦.
    // 지갑에는 더하지 않음 — 이 테스트가 보려는 건 잔여 한도 계산이지 잔액 이력이 아님
    private void persistLegacyCompletedTodoRewardedWith(int rewardAmount) {
        Long todoId = todoService.create(userId,
                new TodoCreateRequest("이전 정책 투두", null, null, TODAY, null, null, null)).id();
        Todo todo = todoRepository.findById(todoId).orElseThrow();
        todo.complete(CurrencyType.COIN, rewardAmount, Instant.now());
        todoRepository.saveAndFlush(todo);
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
