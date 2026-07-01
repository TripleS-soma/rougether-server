package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.todo.dto.TodoCompleteResponse;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
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
class TodoCompletionServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;

    private TodoService service;
    private Long userId;
    private Long todoId;

    @BeforeEach
    void setUp() {
        service = new TodoService(todoRepository, categoryRepository, userRepository,
                userWalletRepository);
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        todoId = service.create(userId, new TodoCreateRequest("장보기", null, null, null)).id();
        persistWallet(user, 0);
    }

    @Test
    void 완료하면_COMPLETED에_코인5가_지급된다() {
        TodoCompleteResponse response = service.complete(userId, todoId);

        assertThat(response.status()).isEqualTo(TodoStatus.COMPLETED);
        assertThat(response.completedAt()).isNotNull();
        assertThat(response.rewardCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(response.rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(5);
    }

    @Test
    void 재완료하면_TODO_ALREADY_COMPLETED() {
        service.complete(userId, todoId);

        assertThatThrownBy(() -> service.complete(userId, todoId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_ALREADY_COMPLETED);
    }

    @Test
    void 취소하면_PENDING으로_되돌리고_코인을_회수한다() {
        service.complete(userId, todoId);

        service.cancelComplete(userId, todoId);

        Todo todo = todoRepository.findById(todoId).orElseThrow();
        assertThat(todo.getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(todo.getCompletedAt()).isNull();
        assertThat(todo.getRewardAmount()).isZero();
        assertThat(todo.getRewardCurrencyType()).isNull();
        assertThat(walletBalance()).isZero();
    }

    @Test
    void 받은_코인을_소비한_뒤_취소하면_잔액이_음수가_되어도_취소된다() {
        service.complete(userId, todoId); // 잔액 5
        spendAllCoins();                  // 다른 곳에서 소비해 잔액 0

        service.cancelComplete(userId, todoId);

        // 음수 잔액을 허용하므로 취소가 성공하고 상태가 PENDING으로 되돌아감
        assertThat(todoRepository.findById(todoId).orElseThrow().getStatus())
                .isEqualTo(TodoStatus.PENDING);
        // 잔액 0에서 5를 회수해 -5가 됨
        assertThat(walletBalance()).isEqualTo(-5);
    }

    @Test
    void 미완료_취소는_TODO_NOT_COMPLETED() {
        assertThatThrownBy(() -> service.cancelComplete(userId, todoId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_NOT_COMPLETED);
    }

    @Test
    void 당일이_아닌_완료_취소는_TODO_NOT_CANCELABLE() {
        Todo todo = todoRepository.findById(todoId).orElseThrow();
        // 어제 완료한 것으로 만들어 당일 조건을 깨뜨림
        Instant yesterday = LocalDate.now(KST).minusDays(1).atStartOfDay(KST).toInstant();
        todo.complete(CurrencyType.COIN, 5, yesterday);
        todoRepository.save(todo);

        assertThatThrownBy(() -> service.cancelComplete(userId, todoId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.TODO_NOT_CANCELABLE);
    }

    private void persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }

    private void spendAllCoins() {
        UserWallet wallet = userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow();
        ReflectionTestUtils.setField(wallet, "balance", 0);
        userWalletRepository.save(wallet);
    }

    private int walletBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
