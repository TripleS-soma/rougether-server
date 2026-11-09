package com.triples.rougether.userapi.routine.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomFurnitureBonusRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.room.service.RoomGrowthService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.reward.service.ChristmasFurnitureBonusService;
import com.triples.rougether.userapi.routine.reward.service.CompletionRewardService;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChristmasRewardTransactionIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);

    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired WalletHistoryRepository histories;
    @Autowired PersonalRoomRepository rooms;
    @Autowired RoutineRepository routines;
    @Autowired RoutineLogRepository logs;
    @Autowired TodoRepository todos;
    @Autowired CategoryRepository categories;
    @Autowired StreakRepository streaks;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager em;

    TransactionTemplate transactions;
    RoomGrowthService growth;
    RoutineLogService routineService;
    TodoService todoService;
    Long userId;
    Long routineId;
    Long todoId;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        var furniture = mock(RoomFurnitureBonusRepository.class);
        when(furniture.countSlotLayoutFurnitureKinds(anyLong(), anyString(), any())).thenReturn(3L);
        var recorder = new WalletHistoryRecorder(histories);
        var rewards = new CompletionRewardService(new DailyRewardService(logs, todos), recorder,
                new ChristmasFurnitureBonusService(furniture, true, true), CLOCK);
        growth = spy(new RoomGrowthService(rooms, users));
        routineService = new RoutineLogService(routines, logs, wallets, streaks, rewards,
                transactions, mock(HouseMissionService.class), recorder, growth);
        todoService = new TodoService(todos, categories, users, wallets, rewards, recorder, growth);
        transactions.executeWithoutResult(tx -> {
            User user = users.save(User.signUp());
            userId = user.getId();
            wallets.save(UserWallet.create(user, CurrencyType.COIN));
            rooms.save(PersonalRoom.create(user));
            routineId = routines.save(Routine.create(user, null, "동시 완료 루틴", AuthType.CHECK,
                    null, null, null, null, null)).getId();
            todoId = todos.save(Todo.create(user, null, "동시 완료 할 일", null, TODAY, null)).getId();
        });
    }

    @AfterEach
    void cleanCommittedFixtures() {
        if (userId == null) return;
        // 외부 트랜잭션을 사용하지 않는 테스트이므로 직접 커밋한 본인 fixture만 정리함.
        transactions.executeWithoutResult(tx -> {
            for (String sql : List.of(
                    "delete from wallet_histories where user_id = :id",
                    "delete from routine_logs where routine_id in (select id from routines where user_id = :id)",
                    "delete from todos where user_id = :id",
                    "delete from streaks where user_id = :id",
                    "delete from routines where user_id = :id",
                    "delete from personal_rooms where user_id = :id",
                    "delete from user_wallets where user_id = :id",
                    "delete from users where id = :id")) {
                em.createNativeQuery(sql).setParameter("id", userId).executeUpdate();
            }
        });
    }

    @Test
    void 남은_원금10에서_루틴과_투두가_동시완료돼도_보너스포함13만_한번_지급한다() throws Exception {
        for (int i = 0; i < 9; i++) {
            Long seedId = transactions.execute(tx -> todos.save(Todo.create(
                    users.getReferenceById(userId), null, "이전 완료", null, TODAY, null)).getId());
            int seedReward = transactions.execute(tx -> todoService.complete(userId, seedId).rewardAmount());
            assertThat(seedReward).isEqualTo(13);
        }
        int initialBalance = transactions.execute(tx -> walletBalance());
        assertThat(initialBalance).isEqualTo(117);
        long historyBefore = transactions.execute(tx -> historyCount());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var routineResult = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return routineService.complete(userId, routineId, new RoutineLogCreateRequest(null)).rewardAmount();
            });
            var todoResult = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return transactions.execute(tx -> todoService.complete(userId, todoId).rewardAmount());
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(routineResult.get(20, TimeUnit.SECONDS), todoResult.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(13, 0);
        }

        transactions.executeWithoutResult(tx -> {
            assertThat(walletBalance()).isEqualTo(130);
            assertThat(historyCount() - historyBefore).isEqualTo(1);
            assertThat(rooms.findById(userId).orElseThrow().getGrowthPoints()).isEqualTo(100);
            int routineBase = logs.sumBaseRewardAmountByRoutine_UserIdAndRoutineDateAndStatus(
                    userId, TODAY, RoutineLogStatus.COMPLETED);
            int todoBase = todos.sumBaseRewardAmountByUserIdAndCompletedAtInKstDay(userId,
                    TODAY.atStartOfDay(ZoneOffset.ofHours(9)).toInstant(),
                    TODAY.plusDays(1).atStartOfDay(ZoneOffset.ofHours(9)).toInstant(), TodoStatus.COMPLETED);
            assertThat(routineBase + todoBase).isEqualTo(100);
            assertThat(todos.findById(todoId).orElseThrow().getStatus()).isEqualTo(TodoStatus.COMPLETED);
            assertThat(logs.findByRoutineIdAndRoutineDateAndStatus(routineId, TODAY, RoutineLogStatus.COMPLETED)).isPresent();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"routine", "todo"})
    void 성장갱신_후_실패하면_완료와_보너스코인_원장_원금이_모두_롤백된다(String kind) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            em.flush();
            throw new IllegalStateException("성장 반영 뒤 강제 실패");
        }).when(growth).award(userId, 10);

        assertThatThrownBy(() -> {
            if (kind.equals("routine")) {
                routineService.complete(userId, routineId, new RoutineLogCreateRequest(null));
            } else {
                transactions.executeWithoutResult(tx -> todoService.complete(userId, todoId));
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("성장 반영 뒤 강제 실패");

        transactions.executeWithoutResult(tx -> {
            assertThat(walletBalance()).isZero();
            assertThat(historyCount()).isZero();
            assertThat(rooms.findById(userId).orElseThrow().getGrowthPoints()).isZero();
            assertThat(logs.findByRoutineIdAndRoutineDateAndStatus(routineId, TODAY, RoutineLogStatus.COMPLETED)).isEmpty();
            Todo pending = todos.findById(todoId).orElseThrow();
            assertThat(pending.getStatus()).isEqualTo(TodoStatus.PENDING);
            assertThat(pending.getRewardAmount()).isZero();
            assertThat(pending.getBaseRewardAmount()).isZero();
            assertThat(pending.getGrowthRewardAmount()).isZero();
        });
    }

    private int walletBalance() {
        return wallets.findByUserIdAndCurrencyType(userId, CurrencyType.COIN).orElseThrow().getBalance();
    }

    private long historyCount() {
        return em.createQuery("select count(h) from WalletHistory h where h.user.id = :id", Long.class)
                .setParameter("id", userId).getSingleResult();
    }
}
