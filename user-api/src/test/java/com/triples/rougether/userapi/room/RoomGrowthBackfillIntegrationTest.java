package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.domain.room.migration.PersonalRoomGrowthBackfill;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 기존 이력에 대한 일괄 mutation이 다른 테스트 fixture를 건드리지 않도록 독립된 실제 MySQL을 사용함.
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:mysql:8.4:///room_growth_backfill")
class RoomGrowthBackfillIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private RoutineRepository routines;
    @Autowired private RoutineLogRepository logs;
    @Autowired private TodoRepository todos;
    @Autowired private RoutineLogService routineService;
    @Autowired private TodoService todoService;
    private final List<Long> userIds = new ArrayList<>();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @AfterEach
    void clean() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM user_characters WHERE user_id = ?", id);
            jdbc.update("DELETE FROM wallet_histories WHERE user_id = ?", id);
            jdbc.update("DELETE FROM streaks WHERE user_id = ?", id);
            jdbc.update("DELETE FROM routine_logs WHERE routine_id IN (SELECT id FROM routines WHERE user_id = ?)", id);
            jdbc.update("DELETE FROM routines WHERE user_id = ?", id);
            jdbc.update("DELETE FROM todos WHERE user_id = ?", id);
            jdbc.update("DELETE FROM personal_rooms WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_wallets WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM characters WHERE code = 'moru'");
    }

    @Test
    void 삭제된_루틴과_투두의_실제보상도_합산하고_레벨5_모루를_중복없이_지급한다() throws Exception {
        User user = user();
        Routine routine = routine(user);
        RoutineLog log = legacyRoutine(routine, 100);
        Todo todo = legacyTodo(user, 20);
        jdbc.update("UPDATE routines SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", routine.getId());
        jdbc.update("UPDATE todos SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", todo.getId());
        jdbc.update("INSERT INTO characters (code,name,base_asset_key,sort_order,is_active) VALUES ('moru','모루','characters/moru/base.png',100,TRUE)");
        migrate();
        migrate();
        assertRoom(user, 120, 5);
        assertThat(jdbc.queryForObject("SELECT growth_reward_amount FROM routine_logs WHERE id = ?", Integer.class, log.getId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT growth_reward_amount FROM todos WHERE id = ?", Integer.class, todo.getId())).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_characters WHERE user_id = ?", Integer.class, user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_selected FROM user_characters WHERE user_id = ?", Boolean.class, user.getId())).isFalse();
        assertThat(balance(user)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_histories", Integer.class)).isZero();
    }

    @Test
    void 기존_포인트와_배치를_보존하고_이미적립된_완료는_다시_더하지_않는다() throws Exception {
        User user = user();
        Todo current = todos.save(Todo.create(user, null, "신규 완료", null, today, null));
        todoService.complete(user.getId(), current.getId());
        jdbc.update("UPDATE personal_rooms SET layout_format='FREE_V1',layout_revision=7 WHERE user_id=?", user.getId());
        legacyRoutine(routine(user), 15);
        Todo partial = legacyTodo(user, 10);
        // 지급 표식이 일부 있는 이행 상태에서도 차액만 반영함.
        jdbc.update("UPDATE todos SET growth_reward_amount=4 WHERE id=?", partial.getId());
        jdbc.update("UPDATE personal_rooms SET growth_points=growth_points+4 WHERE user_id=?", user.getId());
        migrate();
        migrate();
        assertRoom(user, 35, 1);
        assertThat(jdbc.queryForMap("SELECT layout_format,layout_revision FROM personal_rooms WHERE user_id=?", user.getId()))
                .containsEntry("layout_format", "FREE_V1").containsEntry("layout_revision", 7);
        assertThat(balance(user)).isEqualTo(17);
    }

    @Test
    void 미완료_보상0_다른통화_봇_탈퇴사용자는_적립하지_않는다() throws Exception {
        User user = user();
        Routine routine = routine(user);
        logs.save(RoutineLog.fail(routine, today.minusDays(2)));
        legacyRoutine(routine, 0);
        Todo diamond = legacyTodo(user, 30);
        jdbc.update("UPDATE todos SET reward_currency_type='DIAMOND' WHERE id=?", diamond.getId());
        todos.save(Todo.create(user, null, "미완료", null, today, null));
        User bot = user();
        legacyTodo(bot, 20);
        jdbc.update("UPDATE users SET is_bot=TRUE WHERE id=?", bot.getId());
        User withdrawn = user();
        legacyTodo(withdrawn, 20);
        jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", withdrawn.getId());
        migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_rooms", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(growth_reward_amount),0) FROM todos", Integer.class)).isZero();
    }

    @Test
    void 소급한_완료를_실제_API로_취소하고_재완료해도_포인트가_맞는다() throws Exception {
        User user = user();
        Routine routine = routine(user);
        legacyRoutine(routine, 10);
        Todo todo = legacyTodo(user, 10);
        migrate();
        assertRoom(user, 20, 1);
        routineService.cancel(user.getId(), routine.getId(), today);
        todoService.cancelComplete(user.getId(), todo.getId());
        assertRoom(user, 0, 0);
        todoService.complete(user.getId(), todo.getId());
        migrate();
        assertRoom(user, 10, 0);
        assertThat(jdbc.queryForObject("SELECT highest_growth_level FROM personal_rooms WHERE user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void 지급표식_저장_실패시_그_사용자의_포인트와_기록을_함께_롤백하고_재실행한다() throws Exception {
        User user = user();
        legacyRoutine(routine(user), 10);
        legacyTodo(user, 10);
        try (Connection real = dataSource.getConnection()) {
            Connection failing = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0].toString().startsWith("UPDATE todos SET growth_reward_amount")) {
                            throw new SQLException("소급 표식 저장 실패 주입");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            assertThatThrownBy(() -> migrate(failing)).isInstanceOf(SQLException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_rooms WHERE user_id=?", Integer.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(growth_reward_amount) FROM routine_logs", Integer.class)).isZero();
        migrate();
        assertRoom(user, 20, 1);
    }

    @Test
    void 구버전_완료가_코인지갑을_잠근_동안_기다린_후_커밋된_실제보상을_읽는다() throws Exception {
        User user = user();
        Todo todo = todos.save(Todo.create(user, null, "동시 완료", null, today, null));
        try (Connection oldRequest = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            oldRequest.setAutoCommit(false);
            try (var lock = oldRequest.prepareStatement("SELECT id FROM user_wallets WHERE user_id=? FOR UPDATE")) {
                lock.setLong(1, user.getId());
                try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            var migration = pool.submit(() -> { migrate(); return true; });
            try (var complete = oldRequest.prepareStatement("UPDATE todos SET status='COMPLETED',reward_currency_type='COIN',reward_amount=10,completed_at=CURRENT_TIMESTAMP WHERE id=?")) {
                complete.setLong(1, todo.getId());
                complete.executeUpdate();
            }
            oldRequest.commit();
            assertThat(migration.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertRoom(user, 10, 0);
        todoService.cancelComplete(user.getId(), todo.getId());
        assertRoom(user, 0, 0);
    }

    private User user() {
        User user = users.save(User.signUp());
        userIds.add(user.getId());
        UserWallet wallet = wallets.save(UserWallet.create(user, CurrencyType.COIN));
        jdbc.update("UPDATE user_wallets SET balance=7 WHERE id=?", wallet.getId());
        return user;
    }

    private Routine routine(User user) {
        return routines.save(Routine.create(user, null, "기존 루틴", AuthType.CHECK, null, null, null, null, null));
    }

    private RoutineLog legacyRoutine(Routine routine, int amount) {
        return logs.save(RoutineLog.complete(routine, today, Instant.now(), CurrencyType.COIN, amount));
    }

    private Todo legacyTodo(User user, int amount) {
        Todo todo = Todo.create(user, null, "기존 완료", null, today, null);
        todo.complete(CurrencyType.COIN, amount, Instant.now());
        return todos.save(todo);
    }

    private int balance(User user) {
        return jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='COIN'", Integer.class, user.getId());
    }

    private void assertRoom(User user, long points, int level) {
        assertThat(jdbc.queryForMap("SELECT growth_points,growth_level FROM personal_rooms WHERE user_id=?", user.getId()))
                .containsEntry("growth_points", points).containsEntry("growth_level", level);
    }

    private void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            migrate(connection);
        }
    }

    private void migrate(Connection connection) throws Exception {
        new PersonalRoomGrowthBackfill().run(connection);
    }
}
