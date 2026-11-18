package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.service.RoutineService;
import com.triples.rougether.userapi.todo.dto.TodoConvertRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

// 프록시가 붙은 실제 빈으로 전환의 트랜잭션 경계와 user 잠금 직렬화를 본다.
// @DataJpaTest + new Service 는 @Transactional 이 적용되지 않아 롤백·동시성을 증명하지 못한다
@SpringBootTest
class TodoConversionTransactionTest {

    private static final int RACE_ROUNDS = 5;

    @Autowired private TodoConversionService conversionService;
    @Autowired private TodoService todoService;
    @Autowired private TodoRepository todoRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;

    @MockitoSpyBean
    private RoutineService routineService;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함
        for (Long userId : userIds) {
            routineRepository.deleteAll(routineRepository.findAll().stream()
                    .filter(r -> r.getUser().getId().equals(userId)).toList());
            todoRepository.deleteAll(todoRepository.findAll().stream()
                    .filter(t -> t.getUser().getId().equals(userId)).toList());
            userWalletRepository.deleteAll(userWalletRepository.findAll().stream()
                    .filter(w -> w.getUser().getId().equals(userId)).toList());
            userRepository.deleteById(userId);
        }
    }

    @Test
    void 루틴을_저장한_뒤_실패하면_루틴과_투두_삭제가_함께_롤백된다() {
        User user = newUser();
        Long todoId = todoRepository.save(Todo.create(user, null, "독서", null, null, null)).getId();
        // 실제 create(INSERT 까지)를 끝낸 뒤 터뜨려, 이미 쓴 루틴까지 되돌려지는지 본다
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("루틴 저장 뒤 실패");
        }).when(routineService).create(anyLong(), any(RoutineCreateRequest.class));

        assertThatThrownBy(() -> conversionService.convertToRoutine(user.getId(), todoId, dailyRequest(null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(routinesOf(user)).isEmpty();
        assertThat(todoRepository.findById(todoId).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 루틴_검증이_실패하면_아무것도_쓰지_않는다() {
        User user = newUser();
        Long todoId = todoRepository.save(Todo.create(user, null, "산책", null, null, null)).getId();

        assertThatThrownBy(() -> conversionService.convertToRoutine(user.getId(), todoId, dailyRequest(999_999L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);

        assertThat(routinesOf(user)).isEmpty();
        assertThat(todoRepository.findById(todoId).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 같은_투두를_동시에_두_번_전환하면_루틴은_하나만_생기고_나머지는_404다() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            User user = newUser();
            Long todoId = todoRepository.save(Todo.create(user, null, "물 마시기", null, null, null)).getId();

            List<Object> results = race(
                    () -> conversionService.convertToRoutine(user.getId(), todoId, dailyRequest(null)),
                    () -> conversionService.convertToRoutine(user.getId(), todoId, dailyRequest(null)));

            assertThat(routinesOf(user)).hasSize(1);
            assertThat(results).filteredOn(r -> r == TodoErrorCode.TODO_NOT_FOUND).hasSize(1);
            assertThat(todoRepository.findById(todoId).orElseThrow().getDeletedAt()).isNotNull();
        }
    }

    @Test
    void 전환과_완료가_겹쳐도_둘_중_하나만_성공한다() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            User user = newUser();
            persistWallet(user);
            // 마감일 없는 투두 — 완료 보상 0이라 지갑·성장 기록 없이 상태만 바뀐다
            Long todoId = todoRepository.save(Todo.create(user, null, "빨래", null, null, null)).getId();

            List<Object> results = race(
                    () -> conversionService.convertToRoutine(user.getId(), todoId, dailyRequest(null)),
                    () -> todoService.complete(user.getId(), todoId));

            Todo todo = todoRepository.findById(todoId).orElseThrow();
            List<Routine> routines = routinesOf(user);
            if (results.get(1) instanceof ErrorCode) {
                // 전환이 먼저 — 완료는 지운 투두를 못 찾고, 투두는 되살아나지 않는다
                assertThat(results.get(1)).isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
                assertThat(routines).hasSize(1);
                assertThat(todo.getDeletedAt()).isNotNull();
                assertThat(todo.getStatus()).isEqualTo(TodoStatus.PENDING);
            } else {
                // 완료가 먼저 — 전환은 409, 루틴 없음
                assertThat(results.get(0)).isEqualTo(TodoErrorCode.TODO_COMPLETED_NOT_CONVERTIBLE);
                assertThat(routines).isEmpty();
                assertThat(todo.getDeletedAt()).isNull();
                assertThat(todo.getStatus()).isEqualTo(TodoStatus.COMPLETED);
            }
        }
    }

    // 두 작업을 동시에 출발시키고 각 결과(성공 값 또는 BusinessException 의 ErrorCode)를 순서대로 돌려준다
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> awaitAndRun(start, first)),
                    pool.submit(() -> awaitAndRun(start, second)));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object awaitAndRun(CountDownLatch start, Callable<?> task) throws Exception {
        start.await();
        try {
            return task.call();
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    private User newUser() {
        User user = userRepository.save(User.signUp());
        userIds.add(user.getId());
        return user;
    }

    private void persistWallet(User owner) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", 0);
        userWalletRepository.save(wallet);
    }

    private List<Routine> routinesOf(User user) {
        return routineRepository.findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(user.getId());
    }

    private static TodoConvertRequest dailyRequest(Long categoryId) {
        return new TodoConvertRequest("매일 하기", categoryId, AuthType.CHECK, "DAILY", null, null, null, null);
    }
}
