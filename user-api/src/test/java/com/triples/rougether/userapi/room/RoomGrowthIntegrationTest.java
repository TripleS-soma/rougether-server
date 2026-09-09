package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.error.RoutineLogErrorCode;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
import com.triples.rougether.userapi.todo.service.TodoService;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import com.triples.rougether.userapi.onboarding.service.OnboardingCommandService;
import com.triples.rougether.userapi.room.service.RoomGrowthService;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 MySQL 커밋으로 성장 적립·회수·rollback·행 잠금·첫 생성 경합을 검증함.
@SpringBootTest
class RoomGrowthIntegrationTest {

    @Autowired private RoutineLogService routineService;
    @Autowired private TodoService todoService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private RoomCommandService roomCommandService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private RoutineLogRepository logRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private PersonalRoomRepository roomRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;
    @Autowired private MyCharacterQueryService myCharacterQueryService;
    @Autowired private OnboardingQueryService onboardingQueryService;
    @Autowired private OnboardingCommandService onboardingCommandService;
    @Autowired private RoomGrowthService growthService;
    @Autowired private GachaRepository gachaRepository;
    @Autowired private GachaService gachaService;
    private final List<Long> characterIds = new ArrayList<>();
    private final List<Long> gachaIds = new ArrayList<>();

    private final List<Long> userIds = new ArrayList<>();
    private User user;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        user = signUp();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM wallet_histories WHERE user_id = ?", id);
            jdbc.update("DELETE FROM routine_logs WHERE routine_id IN (SELECT id FROM routines WHERE user_id = ?)", id);
            jdbc.update("DELETE FROM streaks WHERE user_id = ?", id);
            jdbc.update("DELETE FROM routines WHERE user_id = ?", id);
            jdbc.update("DELETE FROM todos WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_characters WHERE user_id = ?", id);
            jdbc.update("DELETE FROM personal_rooms WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_wallets WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void cleanCatalog() {
        for (Long id : gachaIds) {
            jdbc.update("DELETE FROM gacha_pool_entries WHERE gacha_id = ?", id);
            jdbc.update("DELETE FROM gacha WHERE id = ?", id);
        }
        // JUnit은 AfterEach 순서를 보장하지 않아 캐릭터 보유도 먼저 지움.
        for (Long id : characterIds) {
            jdbc.update("DELETE FROM user_characters WHERE character_id = ?", id);
            jdbc.update("DELETE FROM characters WHERE id = ?", id);
        }
    }

    @Test
    void 레벨5_직전에는_미지급하고_120포인트_도달시_한번만_지급한다() {
        Character moru = character("moru", true);
        seedGrowth(100, 4);
        Long first = todo(today);
        Long second = todo(today);
        todoService.complete(user.getId(), first);
        assertThat(moruCount(moru)).isZero();
        todoService.complete(user.getId(), second);
        assertThat(moruCount(moru)).isEqualTo(1);
        assertThat(userCharacterRepository.findByUserIdAndCharacterIdAndDeletedAtIsNull(user.getId(), moru.getId())
                .orElseThrow().isSelected()).isFalse();
        todoService.cancelComplete(user.getId(), second);
        assertRoom(110, 4);
        assertThat(roomRepository.findById(user.getId()).orElseThrow().getHighestGrowthLevel()).isEqualTo(5);
        todoService.complete(user.getId(), second);
        assertThatThrownBy(() -> todoService.complete(user.getId(), second)).isInstanceOf(BusinessException.class);
        myCharacterQueryService.getMyCharacters(user.getId());
        assertThat(moruCount(moru)).isEqualTo(1);
    }

    @Test
    void 루틴으로_레벨5를_달성해도_지급하고_기존_대표는_유지한다() {
        Character starter = character("moru-test-starter", true);
        Character moru = character("moru", true);
        userCharacterRepository.save(UserCharacter.createSelected(user, starter));
        seedGrowth(110, 4);
        completeRoutine(routine());
        assertThat(moruCount(moru)).isEqualTo(1);
        assertThat(userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId())
                .orElseThrow().getCharacter().getId()).isEqualTo(starter.getId());
    }

    @Test
    void 한번에_5를_넘겨도_보상을_지급한다() {
        Character moru = character("moru", true);
        tx.executeWithoutResult(status -> {
            growthService.lockUser(user.getId());
            walletRepository.findWithLockByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow();
            growthService.award(user.getId(), 200);
        });
        assertThat(roomRepository.findById(user.getId()).orElseThrow().getGrowthLevel()).isGreaterThan(5);
        assertThat(moruCount(moru)).isEqualTo(1);
    }

    @Test
    void 미등록_동안_달성하고_취소해도_준비된_후_보유목록에서_지급한다() {
        seedGrowth(110, 4);
        Long todoId = todo(today);
        todoService.complete(user.getId(), todoId);
        todoService.cancelComplete(user.getId(), todoId);
        Character moru = character("moru", false);
        assertThat(myCharacterQueryService.getMyCharacters(user.getId()).items()).isEmpty();
        jdbc.update("UPDATE characters SET is_active = true WHERE id = ?", moru.getId());
        assertThat(myCharacterQueryService.getMyCharacters(user.getId()).items()).extracting("code")
                .containsExactly("moru");
        assertRoom(110, 4);
        assertThat(moruCount(moru)).isEqualTo(1);
    }

    @Test
    void 기존_레벨5_이상은_내방_조회로_보정하고_타인_조회는_지급하지_않는다() {
        Character moru = character("moru", true);
        seedGrowth(150, 6);
        roomQueryService.getRoomOf(user.getId());
        assertThat(moruCount(moru)).isZero();
        roomQueryService.getMyRoom(user.getId());
        roomQueryService.getMyRoom(user.getId());
        assertThat(moruCount(moru)).isEqualTo(1);
    }

    @Test
    void 동시_완료와_보유조회가_겹쳐도_모루는_한개만_지급된다() throws Exception {
        Character moru = character("moru", true);
        seedGrowth(110, 4);
        Long todoId = todo(today);
        Long routineId = routine();
        concurrently(List.of(
                () -> { todoService.complete(user.getId(), todoId); return true; },
                () -> { completeRoutine(routineId); return true; },
                () -> { myCharacterQueryService.getMyCharacters(user.getId()); return true; },
                () -> { roomQueryService.getMyRoom(user.getId()); return true; }));
        assertRoom(130, 5);
        assertThat(moruCount(moru)).isEqualTo(1);
    }

    @Test
    void 완료_트랜잭션이_실패하면_레벨달성_기록과_모루도_롤백한다() {
        Character moru = character("moru", true);
        seedGrowth(110, 4);
        Long todoId = todo(today);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            todoService.complete(user.getId(), todoId);
            assertThat(moruCount(moru)).isEqualTo(1);
            throw new IllegalStateException("실패 주입");
        })).isInstanceOf(IllegalStateException.class);
        assertRoom(110, 4);
        assertThat(roomRepository.findById(user.getId()).orElseThrow().getHighestGrowthLevel()).isEqualTo(4);
        assertThat(moruCount(moru)).isZero();
        assertThat(balance()).isZero();
        assertThat(todoRepository.findById(todoId).orElseThrow().getStatus()).isNotEqualTo(TodoStatus.COMPLETED);
    }

    @Test
    void 이미_지급했다가_회수된_캐릭터를_조회로_되살리지_않는다() {
        Character moru = character("moru", true);
        seedGrowth(120, 5);
        roomQueryService.getMyRoom(user.getId());
        jdbc.update("UPDATE user_characters SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = ?", user.getId());
        assertThat(myCharacterQueryService.getMyCharacters(user.getId()).items()).isEmpty();
        assertThat(moruCount(moru)).isEqualTo(1);
    }

    @Test
    void 모루는_온보딩_무료선택에서_제외하고_보상_선취득해도_스타터권을_유지한다() {
        Character moru = character("moru", true);
        Character starter = character("moru-test-starter", true);
        assertThat(onboardingQueryService.getCharacters().items()).extracting("code").doesNotContain("moru");
        assertThatThrownBy(() -> onboardingCommandService.selectCharacter(user.getId(), moru.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode().code())
                        .isEqualTo("CHARACTER_NOT_OWNED"));
        seedGrowth(120, 5);
        myCharacterQueryService.getMyCharacters(user.getId());
        onboardingCommandService.selectCharacter(user.getId(), moru.getId());
        onboardingCommandService.selectCharacter(user.getId(), starter.getId());
        assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(user.getId())).hasSize(2)
                .filteredOn(UserCharacter::isSelected).hasSize(1)
                .extracting(uc -> uc.getCharacter().getId()).containsExactly(starter.getId());
    }

    @Test
    void 잘못_뽑기풀에_등록한_모루도_미리보기와_추첨에서_제외한다() {
        Character moru = character("moru", true);
        Gacha gacha = gachaRepository.save(new Gacha("moru-test-gacha", "검증", CurrencyType.COIN, 500, 1, null, true));
        gachaIds.add(gacha.getId());
        jdbc.update("INSERT INTO gacha_pool_entries (gacha_id, reward_type, character_id, weight, is_active) VALUES (?, 'CHARACTER', ?, 1, true)",
                gacha.getId(), moru.getId());
        jdbc.update("UPDATE user_wallets SET balance = 1000 WHERE user_id = ?", user.getId());
        assertThat(gachaService.getRewards(user.getId(), gacha.getId()).items()).isEmpty();
        assertThatThrownBy(() -> gachaService.draw(user.getId(), gacha.getId(), new GachaDrawRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode().code())
                        .isEqualTo("GACHA_EMPTY_POOL"));
        assertThat(balance()).isEqualTo(1000);
        assertThat(moruCount(moru)).isZero();
    }

    private Character character(String code, boolean active) {
        Character saved = characterRepository.save(new Character(code, code, "characters/" + code + "/base.png", 100, active));
        characterIds.add(saved.getId());
        return saved;
    }

    private void seedGrowth(long points, int level) {
        tx.executeWithoutResult(status -> roomRepository.ensureExists(user.getId()));
        jdbc.update("UPDATE personal_rooms SET growth_points = ?, growth_level = ?, highest_growth_level = ? WHERE user_id = ?",
                points, level, level, user.getId());
    }

    private long moruCount(Character moru) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_characters WHERE user_id = ? AND character_id = ?",
                Long.class, user.getId(), moru.getId());
    }

    @Test
    void 첫_완료로_방을_만들고_완료_종류별_지급량을_합산한다() {
        Long routineId = routine();
        Long todoId = todo(today);
        completeRoutine(routineId);
        todoService.complete(user.getId(), todoId);

        var room = roomQueryService.getMyRoom(user.getId());
        assertThat(room.growthLevel()).isEqualTo(1);
        assertThat(room.growthPoints()).isEqualTo(20);
        assertThat(room.pointsToNextLevel()).isEqualTo(22);
        assertThat(room.layoutRevision()).isZero();
        assertThat(logRepository.findByRoutineIdAndRoutineDate(routineId, today).getFirst()
                .getGrowthRewardAmount()).isEqualTo(10);
        assertThat(todoRepository.findById(todoId).orElseThrow().getGrowthRewardAmount()).isEqualTo(10);
        assertThat(roomQueryService.getRoomOf(user.getId()).growthPoints()).isEqualTo(20);
    }

    @Test
    void 이십_포인트에서_첫_레벨업하고_취소와_재완료로_정확히_되돌아온다() {
        roomQueryService.getMyRoom(user.getId());
        // 이미 한 건을 완료해 적립된 10포인트 상태에서 오늘의 레벨 경계를 검증함.
        jdbc.update("UPDATE personal_rooms SET growth_points = 10 WHERE user_id = ?", user.getId());
        Long routineId = routine();
        completeRoutine(routineId);
        assertRoom(20, 1);
        assertThat(roomQueryService.findRendersOf(List.of(user.getId()), false).get(user.getId()).growthLevel())
                .isEqualTo(1);
        routineService.cancel(user.getId(), routineId, today);
        assertRoom(10, 0);
        completeRoutine(routineId);
        assertRoom(20, 1);
        assertThatThrownBy(() -> completeRoutine(routineId)).isInstanceOf(BusinessException.class);
        assertRoom(20, 1);
    }

    @Test
    void 두번째_레벨_문턱을_넘으면_초과_포인트를_유지하고_취소시_되돌린다() {
        roomQueryService.getMyRoom(user.getId());
        jdbc.update("UPDATE personal_rooms SET growth_points = 40, growth_level = 1 WHERE user_id = ?", user.getId());
        Long todoId = todo(today);
        todoService.complete(user.getId(), todoId);
        assertRoom(50, 2);
        assertThat(roomQueryService.getMyRoom(user.getId()).pointsToNextLevel()).isEqualTo(16);
        todoService.cancelComplete(user.getId(), todoId);
        assertRoom(40, 1);
        assertThat(roomQueryService.getMyRoom(user.getId()).pointsToNextLevel()).isEqualTo(2);
        todoService.complete(user.getId(), todoId);
        assertRoom(50, 2);
        assertThat(roomQueryService.getMyRoom(user.getId()).pointsToNextLevel()).isEqualTo(16);
    }

    @Test
    void 투두_취소_재완료는_지급액을_초기화하고_다시_한번만_적립한다() {
        Long todoId = todo(today);
        todoService.complete(user.getId(), todoId);
        todoService.cancelComplete(user.getId(), todoId);
        assertRoom(0, 0);
        assertThat(todoRepository.findById(todoId).orElseThrow().getGrowthRewardAmount()).isZero();
        todoService.complete(user.getId(), todoId);
        assertRoom(10, 0);
        assertThatThrownBy(() -> todoService.complete(user.getId(), todoId)).isInstanceOf(BusinessException.class);
        assertRoom(10, 0);
    }

    @Test
    void 과거와_실패_이력_소급_완료는_성장_포인트를_주지_않는다() {
        Long pastId = routine();
        routineService.complete(user.getId(), pastId, new RoutineLogCreateRequest(today.minusDays(1)));
        Long failedId = routine();
        logRepository.save(RoutineLog.fail(routineRepository.findById(failedId).orElseThrow(), today.minusDays(1)));
        routineService.complete(user.getId(), failedId, new RoutineLogCreateRequest(today.minusDays(1)));
        todoService.complete(user.getId(), todo(today.minusDays(1)));
        todoService.complete(user.getId(), todo(null));
        assertThat(roomRepository.existsById(user.getId())).isFalse();
        assertThat(logRepository.findByRoutineIdAndRoutineDate(failedId, today.minusDays(1)).getFirst()
                .getGrowthRewardAmount()).isZero();
        assertThat(balance()).isZero();
    }

    @Test
    void 전날_받았던_포인트도_취소시_회수하고_FAILED_재완료는_다시_적립하지_않는다() {
        Long routineId = routineRepository.save(Routine.create(user, null, "매일 루틴", AuthType.CHECK,
                "DAILY", null, null, today.minusDays(2), null)).getId();
        jdbc.update("UPDATE routines SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(today.minusDays(2).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()), routineId);
        completeRoutine(routineId);
        jdbc.update("UPDATE routine_logs SET routine_date = ? WHERE routine_id = ?", today.minusDays(1), routineId);
        routineService.cancel(user.getId(), routineId, today.minusDays(1));
        RoutineLog failed = logRepository.findByRoutineIdAndRoutineDate(routineId, today.minusDays(1)).getFirst();
        assertThat(failed.getStatus()).isEqualTo(RoutineLogStatus.FAILED);
        assertThat(failed.getGrowthRewardAmount()).isZero();
        assertRoom(0, 0);
        routineService.complete(user.getId(), routineId, new RoutineLogCreateRequest(today.minusDays(1)));
        assertRoom(0, 0);
        assertThat(balance()).isZero();
    }

    @Test
    void 하루_상한과_부분_보상은_성장에도_같이_적용되고_삭제로_한도가_복구되지_않는다() {
        Long legacyId = todo(today);
        tx.executeWithoutResult(status -> todoRepository.findById(legacyId).orElseThrow()
                .complete(CurrencyType.COIN, 45, Instant.now()));
        Long partialId = todo(today);
        assertThat(todoService.complete(user.getId(), partialId).rewardAmount()).isEqualTo(5);
        assertRoom(5, 0);
        todoService.delete(user.getId(), partialId);
        assertThat(completeRoutine(routine()).rewardAmount()).isZero();
        assertRoom(5, 0);
    }

    @Test
    void 도입_전_완료를_취소해도_새로_얻은_성장_포인트는_보존한다() {
        Long legacyRoutineId = routine();
        logRepository.save(RoutineLog.complete(routineRepository.findById(legacyRoutineId).orElseThrow(),
                today, Instant.now(), CurrencyType.COIN, 10));
        Long legacyTodoId = todo(today);
        tx.executeWithoutResult(status -> todoRepository.findById(legacyTodoId).orElseThrow()
                .complete(CurrencyType.COIN, 10, Instant.now()));
        completeRoutine(routine());
        routineService.cancel(user.getId(), legacyRoutineId, today);
        todoService.cancelComplete(user.getId(), legacyTodoId);
        assertRoom(10, 0);
    }

    @Test
    void 코인을_소비해도_방_성장은_유지된다() {
        completeRoutine(routine());
        tx.executeWithoutResult(status -> walletRepository.findWithLockByUserIdAndCurrencyType(
                user.getId(), CurrencyType.COIN).orElseThrow().spend(10));
        assertThat(balance()).isZero();
        assertRoom(10, 0);
    }

    @Test
    void 완료_후_트랜잭션이_실패하면_신규_방과_완료_코인_원장_전부_롤백한다() {
        Long routineId = routine();
        Long todoId = todo(today);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            completeRoutine(routineId);
            todoService.complete(user.getId(), todoId);
            throw new IllegalStateException("커밋 직전 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(roomRepository.existsById(user.getId())).isFalse();
        assertThat(logRepository.findByRoutineIdAndRoutineDate(routineId, today)).isEmpty();
        assertThat(todoRepository.findById(todoId).orElseThrow().getStatus()).isEqualTo(TodoStatus.PENDING);
        assertThat(balance()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_histories WHERE user_id = ?", Long.class,
                user.getId())).isZero();
        completeRoutine(routineId);
        todoService.complete(user.getId(), todoId);
        assertRoom(20, 1);
    }

    @Test
    void 취소_후_트랜잭션이_실패하면_완료와_포인트를_함께_보존한다() {
        Long routineId = routine();
        Long todoId = todo(today);
        completeRoutine(routineId);
        todoService.complete(user.getId(), todoId);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            routineService.cancel(user.getId(), routineId, today);
            todoService.cancelComplete(user.getId(), todoId);
            throw new IllegalStateException("커밋 직전 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertRoom(20, 1);
        assertThat(balance()).isEqualTo(20);
        assertThat(logRepository.findByRoutineIdAndRoutineDate(routineId, today).getFirst().getStatus())
                .isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(todoRepository.findById(todoId).orElseThrow().getGrowthRewardAmount()).isEqualTo(10);
    }

    @Test
    void 다른_사람의_완료와_취소는_방_성장을_바꾸지_못한다() {
        User stranger = signUp();
        Long routineId = routine();
        Long todoId = todo(today);
        assertThatThrownBy(() -> routineService.complete(stranger.getId(), routineId, new RoutineLogCreateRequest(null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> todoService.complete(stranger.getId(), todoId)).isInstanceOf(BusinessException.class);
        completeRoutine(routineId);
        todoService.complete(user.getId(), todoId);
        assertThatThrownBy(() -> routineService.cancel(stranger.getId(), routineId, today))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> todoService.cancelComplete(stranger.getId(), todoId))
                .isInstanceOf(BusinessException.class);
        assertRoom(20, 1);
        assertThat(roomRepository.existsById(stranger.getId())).isFalse();
    }

    @Test
    void 루틴과_투두를_동시_완료해도_누락과_하루_상한_초과가_없다() throws Exception {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Long routineId = routine();
            Long todoId = todo(today);
            tasks.add(() -> { completeRoutine(routineId); return true; });
            tasks.add(() -> { todoService.complete(user.getId(), todoId); return true; });
        }
        assertThat(concurrently(tasks)).containsOnly(true).hasSize(8);
        assertRoom(50, 2);
        assertThat(balance()).isEqualTo(50);
    }

    @Test
    void 같은_완료를_동시_취소해도_성장과_코인을_한번만_회수한다() throws Exception {
        Long routineId = routine();
        Long todoId = todo(today);
        completeRoutine(routineId);
        todoService.complete(user.getId(), todoId);
        List<Boolean> results = concurrently(List.of(
                () -> cancelRoutine(routineId), () -> cancelRoutine(routineId),
                () -> cancelTodo(todoId), () -> cancelTodo(todoId)));
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        assertRoom(0, 0);
        assertThat(balance()).isZero();
    }

    @Test
    void 첫_방_조회와_첫_완료가_겹쳐도_포인트가_남는다() throws Exception {
        Long routineId = routine();
        assertThat(concurrently(List.of(
                () -> { roomQueryService.getMyRoom(user.getId()); return true; },
                () -> { completeRoutine(routineId); return true; }))).containsOnly(true);
        assertRoom(10, 0);
    }

    @Test
    void 첫_배치_저장과_첫_완료가_겹쳐도_성장과_배치를_모두_보존한다() throws Exception {
        Long todoId = todo(today);
        assertThat(concurrently(List.of(
                () -> { roomCommandService.updateLayout(user.getId(), new RoomLayoutUpdateRequest(0, List.of(), List.of())); return true; },
                () -> { todoService.complete(user.getId(), todoId); return true; }))).containsOnly(true);
        var room = roomQueryService.getMyRoom(user.getId());
        assertThat(room.growthPoints()).isEqualTo(10);
        assertThat(room.layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        assertThat(room.layoutRevision()).isEqualTo(1);
    }

    @Test
    void 이전에_읽은_투두의_제목_수정이_완료와_성장_지급액을_덮어쓰지_않는다() throws Exception {
        Long todoId = todo(today);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var edit = pool.submit(() -> tx.executeWithoutResult(status -> {
                Todo stale = todoRepository.findById(todoId).orElseThrow();
                loaded.countDown();
                await(completed);
                stale.update("새 제목", null, null, null);
            }));
            try {
                await(loaded);
                todoService.complete(user.getId(), todoId);
            } finally {
                completed.countDown();
            }
            edit.get(20, TimeUnit.SECONDS);
        }
        Todo saved = todoRepository.findById(todoId).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("새 제목");
        assertThat(saved.getStatus()).isEqualTo(TodoStatus.COMPLETED);
        assertThat(saved.getGrowthRewardAmount()).isEqualTo(10);
        todoService.cancelComplete(user.getId(), todoId);
        assertRoom(0, 0);
    }

    private boolean cancelRoutine(Long id) {
        try {
            routineService.cancel(user.getId(), id, today);
            return true;
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(RoutineLogErrorCode.ROUTINE_LOG_NOT_FOUND);
            return false;
        }
    }

    private boolean cancelTodo(Long id) {
        try {
            todoService.cancelComplete(user.getId(), id);
            return true;
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(TodoErrorCode.TODO_NOT_COMPLETED);
            return false;
        }
    }

    private List<Boolean> concurrently(List<Callable<Boolean>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = tasks.stream().map(task -> pool.submit(() -> {
                await(start);
                return task.call();
            })).toList();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private User signUp() {
        User created = userRepository.save(User.signUp());
        userIds.add(created.getId());
        walletRepository.save(UserWallet.create(created, CurrencyType.COIN));
        return created;
    }

    private Long routine() {
        return routineRepository.save(Routine.create(user, null, "테스트 루틴", AuthType.CHECK,
                null, null, null, null, null)).getId();
    }

    private Long todo(LocalDate dueDate) {
        return todoRepository.save(Todo.create(user, null, "테스트 투두", null, dueDate, null)).getId();
    }

    private com.triples.rougether.userapi.routine.dto.RoutineLogResponse completeRoutine(Long id) {
        return routineService.complete(user.getId(), id, new RoutineLogCreateRequest(null));
    }

    private int balance() {
        return walletRepository.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
    }

    private void assertRoom(long points, int level) {
        var room = roomQueryService.getMyRoom(user.getId());
        assertThat(room.growthPoints()).isEqualTo(points);
        assertThat(room.growthLevel()).isEqualTo(level);
    }
}
