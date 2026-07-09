package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class TodoCompletionRollbackTest {

    @Autowired
    private TodoService service;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private UserWalletRepository userWalletRepository;

    private Long userId;
    private Long todoId;
    private Long walletId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (todoId != null) {
            todoRepository.deleteById(todoId);
        }
        if (walletId != null) {
            userWalletRepository.deleteById(walletId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    void 완료_중_지갑_단계가_실패하면_상태와_코인이_전부_롤백된다() {
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        todoId = persistTodo(user);
        walletId = persistWallet(user, 0);

        // 지갑 락 조회 단계에서 터뜨림 — 트랜잭션 전체가 롤백되어 상태·코인 불변이어야 함
        doThrow(new RuntimeException("지갑 조회 실패"))
                .when(userWalletRepository).findWithLockByUserIdAndCurrencyType(any(), any());

        assertThatThrownBy(() -> service.complete(userId, todoId))
                .isInstanceOf(RuntimeException.class);

        assertThat(todoRepository.findById(todoId).orElseThrow().getStatus())
                .isEqualTo(TodoStatus.PENDING);
        assertThat(walletBalance()).isZero();
    }

    @Test
    void 취소_중_지갑_단계가_실패하면_상태와_코인이_전부_롤백된다() {
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        todoId = persistCompletedTodo(user);
        walletId = persistWallet(user, 5);

        doThrow(new RuntimeException("지갑 조회 실패"))
                .when(userWalletRepository).findWithLockByUserIdAndCurrencyType(any(), any());

        assertThatThrownBy(() -> service.cancelComplete(userId, todoId))
                .isInstanceOf(RuntimeException.class);

        assertThat(todoRepository.findById(todoId).orElseThrow().getStatus())
                .isEqualTo(TodoStatus.COMPLETED);
        assertThat(walletBalance()).isEqualTo(5);
    }

    private Long persistTodo(User owner) {
        return todoRepository.save(Todo.create(owner, null, "장보기", null, null)).getId();
    }

    private Long persistCompletedTodo(User owner) {
        Todo todo = Todo.create(owner, null, "장보기", null, null);
        todo.complete(CurrencyType.COIN, 5, Instant.now());
        return todoRepository.save(todo).getId();
    }

    private Long persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        return userWalletRepository.save(wallet).getId();
    }

    private int walletBalance() {
        return userWalletRepository.findById(walletId).orElseThrow().getBalance();
    }
}
