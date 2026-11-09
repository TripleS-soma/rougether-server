package com.triples.rougether.userapi.routine.reward.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.triples.rougether.domain.member.entity.*;
import com.triples.rougether.domain.member.repository.*;
import com.triples.rougether.domain.room.entity.*;
import com.triples.rougether.domain.room.repository.*;
import com.triples.rougether.domain.routine.entity.*;
import com.triples.rougether.domain.routine.repository.*;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.*;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.room.service.RoomGrowthService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class ChristmasFurnitureBonusIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired WalletHistoryRepository histories;
    @Autowired RoutineRepository routines;
    @Autowired RoutineLogRepository logs;
    @Autowired TodoRepository todos;
    @Autowired CategoryRepository categories;
    @Autowired StreakRepository streaks;
    @Autowired PersonalRoomRepository rooms;
    @Autowired RoomFurnitureBonusRepository furniture;
    @Autowired PlatformTransactionManager transactionManager;
    User user;
    PersonalRoom room;
    Theme christmas;
    CompletionRewardService rewards;
    RoutineLogService routineService;
    TodoService todoService;

    @BeforeEach
    void setup() {
        user = users.save(User.signUp());
        wallets.save(UserWallet.create(user, CurrencyType.COIN));
        room = rooms.save(PersonalRoom.create(user));
        christmas = new Theme("snowy_christmas_202611", "테스트 크리스마스", null, false);
        em.persist(christmas);
        configure(true, NOW);
    }

    private void configure(boolean enabled, Instant time) {
        var recorder = new WalletHistoryRecorder(histories);
        rewards = new CompletionRewardService(new DailyRewardService(logs, todos), recorder,
                new ChristmasFurnitureBonusService(furniture, enabled, true), Clock.fixed(time, ZoneOffset.UTC));
        var growth = new RoomGrowthService(rooms, users);
        routineService = new RoutineLogService(routines, logs, wallets, streaks, rewards,
                new TransactionTemplate(transactionManager), mock(HouseMissionService.class), recorder, growth);
        todoService = new TodoService(todos, categories, users, wallets, rewards, recorder, growth);
    }

    @Test
    void 슬롯은_서로다른_가구만_세고_벽지바닥_보관중_다른테마_비활성보유는_제외한다() {
        var chair = own(christmas, "furniture", "positioned");
        slot("topLeft", chair);
        slot("topRight", chair);
        slot("floor", own(christmas, "floor", "surface_slot"));
        slot("wallpaper", own(christmas, "wallpaper", "surface_slot"));
        var inactive = own(christmas, "furniture", "positioned");
        inactive.deactivate(NOW);
        slot("midLeft", inactive);
        own(christmas, "furniture", "positioned");
        var other = new Theme("bonus-test-other", "다른 테마", null, true);
        em.persist(other);
        slot("bottomRight", own(other, "furniture", "positioned"));
        // SLOT_V1에서는 남은 자유배치 행을 합산하지 않는다.
        place(own(christmas, "furniture", "positioned"));
        assertThat(rewards.calculateReward(user.getId(), TODAY, TODAY).totalAmount()).isEqualTo(11);
    }

    @Test
    void 자유배치가_정본이면_옛슬롯을_무시하고_유효한_자기소유_가구만_센다() {
        slot("topLeft", own(christmas, "furniture", "positioned"));
        room.convertToFreeLayout();
        place(own(christmas, "furniture", "positioned"));
        place(own(christmas, "furniture", "positioned"));
        place(own(christmas, "floor", "surface_slot"));
        var inactive = own(christmas, "furniture", "positioned");
        inactive.deactivate(NOW);
        place(inactive);
        User stranger = users.save(User.signUp());
        Item item = item(christmas, "furniture", "positioned");
        UserItem foreign = UserItem.create(stranger, item);
        em.persist(foreign);
        place(foreign);
        assertThat(rewards.calculateReward(user.getId(), TODAY, TODAY).totalAmount()).isEqualTo(12);
    }

    @Test
    void 혼합_10건은_원금100_보너스30_성장100이며_11번째는0_취소는_실지급액을_회수한다() {
        threeKinds();
        Long firstTodo = null;
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                Long id = todo(TODAY);
                if (firstTodo == null) firstTodo = id;
                assertThat(todoService.complete(user.getId(), id).rewardAmount()).isEqualTo(13);
                assertThat(todos.findById(id).orElseThrow().getBaseRewardAmount()).isEqualTo(10);
            } else {
                var response = routineService.complete(user.getId(), routine(), new RoutineLogCreateRequest(null));
                assertThat(response.rewardAmount()).isEqualTo(13);
                assertThat(logs.findById(response.id()).orElseThrow().getBaseRewardAmount()).isEqualTo(10);
            }
        }
        assertThat(balance()).isEqualTo(130);
        assertThat(room.getGrowthPoints()).isEqualTo(100);
        assertThat(todoService.complete(user.getId(), todo(TODAY)).rewardAmount()).isZero();
        // 배치를 모두 제거한 뒤에도 실제 지급13과 성장10을 각각 회수한다.
        em.createQuery("delete from RoomSurfaceSlot s where s.room.userId = :id").setParameter("id", user.getId()).executeUpdate();
        todoService.cancelComplete(user.getId(), firstTodo);
        assertThat(balance()).isEqualTo(117);
        assertThat(room.getGrowthPoints()).isEqualTo(90);
        assertThat(todoService.complete(user.getId(), firstTodo).rewardAmount()).isEqualTo(10);
        assertThat(balance()).isEqualTo(127);
        assertThat(room.getGrowthPoints()).isEqualTo(100);
    }

    @Test
    void 루틴도_완료후_배치와_기간이_바뀌어도_저장한_총액과_성장을_취소한다() {
        threeKinds();
        Long id = routine();
        var response = routineService.complete(user.getId(), id, new RoutineLogCreateRequest(null));
        assertThat(response.rewardAmount()).isEqualTo(13);
        em.createQuery("delete from RoomSurfaceSlot s where s.room.userId = :id").setParameter("id", user.getId()).executeUpdate();
        configure(true, Instant.parse("2026-12-26T03:00:00Z"));
        routineService.cancel(user.getId(), id, TODAY);
        assertThat(balance()).isZero();
        assertThat(room.getGrowthPoints()).isZero();
    }

    @Test
    void legacy_NULL원금은_실지급으로_집계하고_부분원금5에_보너스1만_추가한다() {
        threeKinds();
        Todo legacy = todos.findById(todo(TODAY)).orElseThrow();
        legacy.complete(CurrencyType.COIN, 95, NOW);
        em.flush();
        em.createNativeQuery("update todos set base_reward_amount=null where id=:id").setParameter("id", legacy.getId()).executeUpdate();
        em.refresh(legacy);
        assertThat(legacy.getBaseRewardAmount()).isEqualTo(95);
        Long id = todo(TODAY);
        assertThat(todoService.complete(user.getId(), id).rewardAmount()).isEqualTo(6);
        assertThat(todos.findById(id).orElseThrow().getBaseRewardAmount()).isEqualTo(5);
        assertThat(room.getGrowthPoints()).isEqualTo(5);
        assertThat(todoService.complete(user.getId(), todo(TODAY)).rewardAmount()).isZero();
    }

    @Test
    void 과거와_마감일없음은0_플래그OFF와_기간종료는_기본보상만_지급한다() {
        threeKinds();
        assertThat(todoService.complete(user.getId(), todo(TODAY.minusDays(1))).rewardAmount()).isZero();
        assertThat(todoService.complete(user.getId(), todo(null)).rewardAmount()).isZero();
        configure(false, NOW);
        assertThat(todoService.complete(user.getId(), todo(TODAY)).rewardAmount()).isEqualTo(10);
        configure(true, Instant.parse("2026-12-25T15:00:00Z"));
        LocalDate ended = LocalDate.of(2026, 12, 26);
        assertThat(todoService.complete(user.getId(), todo(ended)).rewardAmount()).isEqualTo(10);
        assertThat(todoService.complete(user.getId(), todo(TODAY)).rewardAmount()).isZero();
    }

    @Test
    void 보너스없는_신규행과_취소행은_구버전SQL완료후에도_원금이_실지급액을_따른다() {
        configure(false, NOW);
        Long pendingId = todo(TODAY);
        em.flush();
        oldComplete(pendingId, 10);
        assertThat(new DailyRewardService(logs, todos).remainingReward(user.getId(), TODAY)).isEqualTo(90);
        Long completedId = todo(TODAY);
        todoService.complete(user.getId(), completedId);
        todoService.cancelComplete(user.getId(), completedId);
        em.flush();
        assertThat(em.createNativeQuery("select base_reward_amount from todos where id=:id")
                .setParameter("id", completedId).getSingleResult()).isNull();
        oldComplete(completedId, 10);
        assertThat(new DailyRewardService(logs, todos).remainingReward(user.getId(), TODAY)).isEqualTo(80);
        Long routineId = routine();
        var log = RoutineLog.complete(routines.findById(routineId).orElseThrow(), TODAY, NOW, CurrencyType.COIN, 5);
        logs.saveAndFlush(log);
        em.createNativeQuery("update routine_logs set reward_amount=10 where id=:id").setParameter("id", log.getId()).executeUpdate();
        assertThat(new DailyRewardService(logs, todos).remainingReward(user.getId(), TODAY)).isEqualTo(70);
    }

    private void oldComplete(Long id, int amount) {
        em.createNativeQuery("update todos set status='COMPLETED',reward_currency_type='COIN',reward_amount=:amount,completed_at=:time where id=:id")
                .setParameter("amount", amount).setParameter("time", NOW).setParameter("id", id).executeUpdate();
        em.refresh(todos.findById(id).orElseThrow());
    }

    private Long routine() {
        return routines.save(Routine.create(user, null, "테스트 루틴", AuthType.CHECK, null, null, null, null, null)).getId();
    }
    private Long todo(LocalDate dueDate) {
        return todos.save(Todo.create(user, null, "테스트 할 일", null, dueDate, null)).getId();
    }
    private int balance() {
        return wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
    }
    private void threeKinds() {
        slot("topLeft", own(christmas, "furniture", "positioned"));
        slot("topCenter", own(christmas, "furniture", "positioned"));
        slot("topRight", own(christmas, "furniture", "positioned"));
    }
    private Item item(Theme theme, String category, String placement) {
        Item item = new Item(theme, category, placement, null, null, "테스트 가구", null, null, "test/bonus.png", true, false);
        em.persist(item);
        return item;
    }
    private UserItem own(Theme theme, String category, String placement) {
        UserItem owned = UserItem.create(user, item(theme, category, placement));
        em.persist(owned);
        return owned;
    }
    private void slot(String name, UserItem owned) { em.persist(RoomSurfaceSlot.create(room, name, owned)); }
    private void place(UserItem owned) {
        em.persist(RoomItemPlacement.place(room, owned, new BigDecimal("0.5"), new BigDecimal("0.5"), 0, BigDecimal.ONE, 0, false));
    }
}
