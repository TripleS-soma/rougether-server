package com.triples.rougether.userapi.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.service.RoutineService;
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class CalendarServiceIntegrationTest {

    // 2026-06-29는 월요일(MON)이자 확정 과거 — 과거 경로(로그 기반) 검증용
    private static final LocalDate PAST = LocalDate.of(2026, 6, 29);
    // 오늘(KST) — 오늘·미래 경로(live 재계산) 검증용
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private CalendarService service;
    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        service = new CalendarService(routineRepository, routineLogRepository, todoRepository,
                new DailyAgendaAssembler());
        user = userRepository.save(User.signUp());
        userId = user.getId();
    }

    // --- 오늘·미래: live 재계산 경로 ---

    @Test
    void 오늘은_DAILY_루틴이_로그_없이도_대상으로_노출된다() {
        persistRoutine("매일 운동", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        assertThat(routineTitles(service.day(userId, TODAY))).containsExactly("매일 운동");
    }

    @Test
    void 오늘_WEEKLY_루틴은_해당_요일만_대상이고_다른_요일은_제외된다() {
        String todayToken = weekdayToken(TODAY);
        String otherToken = weekdayToken(TODAY.plusDays(1));
        persistRoutine("오늘 요일 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"" + todayToken + "\"]}", null, null, null, null);
        persistRoutine("다른 요일 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"" + otherToken + "\"]}", null, null, null, null);

        assertThat(routineTitles(service.day(userId, TODAY))).containsExactly("오늘 요일 루틴");
    }

    @Test
    void 오늘_기준_시작전이거나_종료후면_제외된다() {
        persistRoutine("아직 시작 안 함", RoutineStatus.ACTIVE, "DAILY", null, null,
                TODAY.plusDays(1), null, null);
        persistRoutine("이미 종료됨", RoutineStatus.ACTIVE, "DAILY", null, null,
                null, TODAY.minusDays(1), null);
        persistRoutine("기간 내", RoutineStatus.ACTIVE, "DAILY", null, null,
                TODAY.minusDays(3), TODAY.plusDays(3), null);

        assertThat(routineTitles(service.day(userId, TODAY))).containsExactly("기간 내");
    }

    @Test
    void 오늘은_그날_완료_log가_있으면_completed_true_없으면_false() {
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistCompletedLog(done, TODAY);

        List<TodayRoutineItem> routines = service.day(userId, TODAY).categories().get(0).routines();

        assertThat(routines).filteredOn(r -> r.title().equals("완료 루틴"))
                .extracting(TodayRoutineItem::completed).containsExactly(true);
        assertThat(routines).filteredOn(r -> r.title().equals("미완료 루틴"))
                .extracting(TodayRoutineItem::completed).containsExactly(false);
    }

    @Test
    void 오늘_summary는_그날_대상_기준_완료_미완료_진행률을_계산한다() {
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistCompletedLog(done, TODAY);
        persistCompletedTodo("완료 투두", null, TODAY);
        persistTodo("미완료 투두", null, TODAY);

        var summary = service.day(userId, TODAY).summary();

        // 완료: 루틴1 + 투두1 = 2, 전체: 루틴2 + 투두2 = 4
        assertThat(summary.completedCount()).isEqualTo(2);
        assertThat(summary.remainingCount()).isEqualTo(2);
        assertThat(summary.progressRate()).isEqualTo(0.5);
    }

    @Test
    void 미래_날짜도_조회할_수_있다() {
        LocalDate future = TODAY.plusYears(1);
        persistRoutine("미래 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        CalendarDayResponse response = service.day(userId, future);

        assertThat(response.date()).isEqualTo(future);
        assertThat(routineTitles(response)).containsExactly("미래 루틴");
    }

    @Test
    void 오늘_투두는_마감일이_그날인_것만_노출되고_지난_마감과_미래_마감은_제외된다() {
        persistTodo("그날 마감", null, TODAY);
        persistTodo("지난 마감", null, TODAY.minusDays(2));
        persistTodo("미래 마감", null, TODAY.plusDays(1));

        assertThat(todoTitles(service.day(userId, TODAY))).containsExactly("그날 마감");
    }

    @Test
    void 오늘_카테고리별로_묶이고_미분류는_별도_그룹으로_맨_뒤에_온다() {
        Category category = persistCategory("운동");
        persistRoutine("분류 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, category);
        persistRoutine("미분류 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistTodo("분류 투두", category, TODAY);
        persistTodo("미분류 투두", null, TODAY);

        var groups = service.day(userId, TODAY).categories();

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).categoryId()).isEqualTo(category.getId());
        assertThat(groups.get(1).categoryId()).isNull();
        assertThat(groups.get(1).todos()).extracting(t -> t.title()).containsExactly("미분류 투두");
    }

    @Test
    void 오늘_투두의_완료_상태와_완료시각이_그대로_노출된다() {
        persistCompletedTodo("완료 투두", null, TODAY);
        persistTodo("미완료 투두", null, TODAY);

        var todos = service.day(userId, TODAY).categories().get(0).todos();

        assertThat(todos).filteredOn(t -> t.title().equals("완료 투두"))
                .allSatisfy(t -> {
                    assertThat(t.status()).isEqualTo(TodoStatus.COMPLETED);
                    assertThat(t.completedAt()).isNotNull();
                });
        assertThat(todos).filteredOn(t -> t.title().equals("미완료 투두"))
                .allSatisfy(t -> {
                    assertThat(t.status()).isEqualTo(TodoStatus.PENDING);
                    assertThat(t.completedAt()).isNull();
                });
    }

    @Test
    void 타인_소유_루틴과_투두는_노출되지_않는다() {
        User other = userRepository.save(User.signUp());
        routineRepository.save(Routine.create(other, null, "남의 루틴", AuthType.CHECK,
                "DAILY", null, null, null, null));
        todoRepository.save(Todo.create(other, null, "남의 투두", null, TODAY));

        CalendarDayResponse response = service.day(userId, TODAY);

        assertThat(response.categories()).isEmpty();
        assertThat(response.summary().completedCount()).isZero();
        assertThat(response.summary().remainingCount()).isZero();
    }

    // --- 과거: 그날 유효했던 버전 재구성 경로 ---

    @Test
    void 과거_그날_대상_버전은_로그가_없어도_노출되고_완료는_로그로_판정된다() {
        // 두 루틴 모두 PAST 이전에 생성된 것으로 당겨 그날 유효하게 만듦
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        Long undone = persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        backdateCreatedAt(done, 20);
        backdateCreatedAt(undone, 20);
        persistCompletedLog(done, PAST);

        List<TodayRoutineItem> routines = service.day(userId, PAST).categories().get(0).routines();

        // 미완료(무로그)도 노출됨 — 그날 유효한 버전 + 반복 대상이므로
        assertThat(routines).extracting(TodayRoutineItem::title)
                .containsExactlyInAnyOrder("완료 루틴", "미완료 루틴");
        assertThat(routines).filteredOn(r -> r.title().equals("완료 루틴"))
                .extracting(TodayRoutineItem::completed).containsExactly(true);
        assertThat(routines).filteredOn(r -> r.title().equals("미완료 루틴"))
                .extracting(TodayRoutineItem::completed).containsExactly(false);
    }

    @Test
    void 과거_생성_이전_날짜에는_노출되지_않는다() {
        // 오늘 생성분(created_at=today) — PAST엔 아직 존재하지 않았으므로 노출 안 됨
        persistRoutine("오늘 만든 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        assertThat(service.day(userId, PAST).categories()).isEmpty();
    }

    @Test
    void 과거_스케줄_수정_후에도_옛_버전_기준으로_재구성된다() {
        // PAST(월요일) 이전에 생성된 WEEKLY MON 루틴
        Long routineId = persistRoutine("주간 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"MON\"]}", null, null, null, null);
        backdateCreatedAt(routineId, 20);

        // 스케줄을 TUE로 변경 → 새 버전으로 분기(옛 MON 버전은 닫힘)
        RoutineService routineService = new RoutineService(routineRepository, categoryRepository,
                userRepository);
        routineService.update(userId, routineId, new RoutineUpdateRequest(null, null, null,
                "WEEKLY", new RepeatDays(List.of("TUE")), null, null, null));
        em.flush();
        em.clear();

        // 과거(PAST=MON)는 옛 MON 버전으로 재구성 — 편집에 영향받지 않음
        assertThat(routineTitles(service.day(userId, PAST))).containsExactly("주간 루틴");
    }

    @Test
    void 분기_경계에서_과거는_옛_버전_당일은_새_버전만_노출한다() {
        // 오늘도 유효하도록 옛 버전은 오늘 요일 WEEKLY, PAST 이전에 생성된 것으로 당김
        String todayToken = weekdayToken(TODAY);
        Long routineId = persistRoutine("경계 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"" + todayToken + "\"]}", null, null, null, null);
        backdateCreatedAt(routineId, 20);

        // 스케줄을 DAILY로 변경 → 분기(옛 WEEKLY 버전 닫힘, 새 DAILY 버전 생성)
        RoutineService routineService = new RoutineService(routineRepository, categoryRepository,
                userRepository);
        RoutineResponse neo = routineService.update(userId, routineId,
                new RoutineUpdateRequest(null, null, null, "DAILY", null, null, null, null));
        em.flush();
        em.clear();

        // 과거(옛 버전이 유효했던 요일): 옛 버전 하나만 — 새 버전은 생성 이전이라 제외(공백·겹침 없음)
        LocalDate pastSameWeekday = TODAY.minusWeeks(2);
        assertThat(routineTitles(service.day(userId, pastSameWeekday))).containsExactly("경계 루틴");

        // 당일: 새 DAILY 버전 하나만 — 옛 버전은 닫혀 live 경로에서 제외
        assertThat(service.day(userId, TODAY).categories().stream()
                .flatMap(g -> g.routines().stream()))
                .extracting(TodayRoutineItem::id)
                .containsExactly(neo.id());
    }

    @Test
    void 과거_삭제된_버전도_자기_유효기간_안에서는_노출된다() {
        Long routineId = persistRoutine("삭제될 루틴", RoutineStatus.ACTIVE, "DAILY",
                null, null, null, null, null);
        backdateCreatedAt(routineId, 20);
        // 오늘 soft-delete(deleted_at=today) — PAST 시점엔 아직 살아 있었음
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routine.softDelete(Instant.now());
        routineRepository.save(routine);
        em.flush();
        em.clear();

        assertThat(routineTitles(service.day(userId, PAST))).containsExactly("삭제될 루틴");
    }

    @Test
    void 과거_완료로그만_있고_유효기간_밖이어도_로그로_노출된다() {
        // created_at=today라 PAST엔 유효하지 않지만, PAST에 완료 로그가 있으면 union으로 노출
        Long routineId = persistRoutine("나중에 만든 루틴", RoutineStatus.ACTIVE, "DAILY",
                null, null, null, null, null);
        persistCompletedLog(routineId, PAST);

        List<TodayRoutineItem> routines = service.day(userId, PAST).categories().get(0).routines();

        assertThat(routines).extracting(TodayRoutineItem::title).containsExactly("나중에 만든 루틴");
        assertThat(routines).extracting(TodayRoutineItem::completed).containsExactly(true);
    }

    @Test
    void 과거_로그가_삭제된_루틴_카테고리를_가리켜도_categoryId로_노출된다() {
        Category category = persistCategory("삭제될 카테고리");
        Long routineId = persistRoutine("삭제될 루틴", RoutineStatus.ACTIVE, "DAILY",
                null, null, null, null, category);
        persistCompletedLog(routineId, PAST);

        // 루틴·카테고리를 soft-delete — 과거 로그는 여전히 이들을 가리킴
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routine.softDelete(Instant.now());
        routineRepository.save(routine);
        category.softDelete(Instant.now());
        categoryRepository.save(category);

        var groups = service.day(userId, PAST).categories();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).categoryId()).isEqualTo(category.getId());
        assertThat(groups.get(0).routines()).extracting(TodayRoutineItem::title)
                .containsExactly("삭제될 루틴");
        assertThat(groups.get(0).routines().get(0).completed()).isTrue();
    }

    @Test
    void 과거_루틴은_기록_당시가_아니라_현재_루틴의_최신_title로_노출된다() {
        Long routineId = persistRoutine("원래 제목", RoutineStatus.ACTIVE, "DAILY",
                null, null, null, null, null);
        persistCompletedLog(routineId, PAST);

        // 로그 이후 루틴 title 변경 — 과거 조회는 스냅숏이 아니라 현재값을 읽어야 함
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routine.update("바뀐 제목", null, null, null, null, null, null);
        routineRepository.save(routine);

        assertThat(routineTitles(service.day(userId, PAST))).containsExactly("바뀐 제목");
    }

    @Test
    void 과거_summary는_완료_루틴과_그날_투두_기준으로_계산된다() {
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        // log 없는 과거 루틴은 총계에서 제외됨
        persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistCompletedLog(done, PAST);
        persistCompletedTodo("완료 투두", null, PAST);
        persistTodo("미완료 투두", null, PAST);

        var summary = service.day(userId, PAST).summary();

        // 노출 루틴 = 완료 1, 투두 = 총 2(완료 1) → 전체 3, 완료 2, 남은 1
        assertThat(summary.completedCount()).isEqualTo(2);
        assertThat(summary.remainingCount()).isEqualTo(1);
    }

    private List<String> routineTitles(CalendarDayResponse response) {
        return response.categories().stream()
                .flatMap(g -> g.routines().stream())
                .map(TodayRoutineItem::title)
                .toList();
    }

    private List<String> todoTitles(CalendarDayResponse response) {
        return response.categories().stream()
                .flatMap(g -> g.todos().stream())
                .map(t -> t.title())
                .toList();
    }

    // LocalDate의 요일을 저장 토큰(MON~SUN) 형태로
    private String weekdayToken(LocalDate date) {
        return date.getDayOfWeek().name().substring(0, 3);
    }

    private Long persistRoutine(String title, RoutineStatus status, String repeatType,
                                String repeatDays, LocalTime scheduledTime,
                                LocalDate startsOn, LocalDate endsOn, Category category) {
        Routine routine = Routine.create(user, category, title, AuthType.CHECK,
                repeatType, repeatDays, scheduledTime, startsOn, endsOn);
        if (status != RoutineStatus.ACTIVE) {
            ReflectionTestUtils.setField(routine, "status", status);
        }
        return routineRepository.save(routine).getId();
    }

    private void persistCompletedLog(Long routineId, LocalDate date) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routineLogRepository.save(RoutineLog.complete(routine, date, Instant.now(),
                CurrencyType.COIN, 10));
    }

    private void persistTodo(String title, Category category, LocalDate dueDate) {
        todoRepository.save(Todo.create(user, category, title, null, dueDate));
    }

    private void persistCompletedTodo(String title, Category category, LocalDate dueDate) {
        Todo todo = Todo.create(user, category, title, null, dueDate);
        todo.complete(CurrencyType.COIN, 5, Instant.now());
        todoRepository.save(todo);
    }

    private Category persistCategory(String name) {
        return categoryRepository.save(
                Category.create(user, name, "#FFFFFF", null, 0, PrivacyScope.PRIVATE));
    }

    // created_at은 auditing이 now로 채우고 updatable=false라 JPA로 못 바꿈 → 네이티브로 N일 과거로 당김
    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery(
                "update routines set created_at = created_at - interval " + days
                        + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
