package com.triples.rougether.userapi.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.calendar.dto.CalendarDayCount;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.calendar.dto.CalendarMonthResponse;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.service.RoutineService;
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

// 월별 개수 조회(CalendarService.month). 날짜별 소싱 규칙 자체는 CalendarServiceIntegrationTest 가 검증하고,
// 여기서는 구간별 집계가 day() 와 같은 답을 내는지·달 전체 구조가 맞는지를 본다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class CalendarMonthIntegrationTest {

    // 2026-06은 확정 과거 달 — 그제 이전(로그 단독) 경로만 타는 달
    private static final YearMonth PAST_MONTH = YearMonth.of(2026, 6);
    // 2026-06-29는 월요일
    private static final LocalDate PAST = LocalDate.of(2026, 6, 29);
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneId.of("Asia/Seoul"));
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

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
    @Autowired
    private HouseRepository houseRepository;
    @Autowired
    private HouseMissionRepository houseMissionRepository;
    @Autowired
    private HouseMemberRepository houseMemberRepository;

    @PersistenceContext
    private EntityManager em;

    private CalendarService service;
    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        service = new CalendarService(routineRepository, routineLogRepository, todoRepository,
                new DailyAgendaAssembler(), CLOCK);
        user = userRepository.save(User.signUp());
        userId = user.getId();
    }

    // --- 응답 구조 ---

    @Test
    void 그_달의_모든_날짜를_1일부터_말일까지_순서대로_담고_대상이_없는_날은_0이다() {
        CalendarMonthResponse response = service.month(userId, PAST_MONTH);

        assertThat(response.yearMonth()).isEqualTo(PAST_MONTH);
        assertThat(response.days()).hasSize(30);
        assertThat(response.days()).extracting(CalendarDayCount::date)
                .containsExactlyElementsOf(PAST_MONTH.atDay(1)
                        .datesUntil(PAST_MONTH.atEndOfMonth().plusDays(1)).toList());
        assertThat(response.days()).allSatisfy(day -> {
            assertThat(day.routineCount()).isZero();
            assertThat(day.todoCount()).isZero();
            assertThat(day.routineCompletedCount()).isZero();
            assertThat(day.todoCompletedCount()).isZero();
        });
    }

    // --- 오늘·미래: ACTIVE 루틴 live 재계산 ---

    @Test
    void 오늘_이후_날짜는_ACTIVE_루틴의_반복_대상_여부로_센다() {
        persistRoutine("매일", "DAILY", null, null, null);
        persistRoutine("주간", "WEEKLY", "{\"daysOfWeek\":[\"" + weekdayToken(TODAY) + "\"]}", null, null);
        // 미래에 시작하는 루틴은 시작일 전에는 세지 않음
        LocalDate startsLater = TODAY.plusDays(3);
        persistRoutine("나중 시작", "DAILY", null, startsLater, null);

        YearMonth month = YearMonth.from(TODAY);
        CalendarMonthResponse response = service.month(userId, month);

        // 오늘: 매일 + 주간(오늘 요일)
        assertThat(countOn(response, TODAY).routineCount()).isEqualTo(2);
        // 오늘 이후 다른 요일: 매일만(+ 시작일 이후면 나중 시작도)
        for (LocalDate date = TODAY.plusDays(1); !date.isAfter(month.atEndOfMonth()); date = date.plusDays(1)) {
            int expected = 1;
            if (date.getDayOfWeek() == TODAY.getDayOfWeek()) {
                expected++;
            }
            if (!date.isBefore(startsLater)) {
                expected++;
            }
            assertThat(countOn(response, date).routineCount())
                    .as("date=%s", date).isEqualTo(expected);
        }
    }

    @Test
    void 통째로_미래인_달도_조회되고_모든_날짜가_live_재계산이다() {
        persistRoutine("매일", "DAILY", null, null, null);
        YearMonth nextMonth = YearMonth.from(TODAY).plusMonths(1);

        CalendarMonthResponse response = service.month(userId, nextMonth);

        assertThat(response.days()).hasSize(nextMonth.lengthOfMonth());
        assertThat(response.days()).extracting(CalendarDayCount::routineCount).containsOnly(1);
        assertThat(response.days()).extracting(CalendarDayCount::routineCompletedCount).containsOnly(0);
    }

    // --- 그제 이전: 로그 단독 집계 ---

    @Test
    void 그제_이전은_그날_로그_건수로_세고_로그가_없는_날은_대상_루틴이_있어도_0이다() {
        Long done = persistRoutine("완료 루틴", "DAILY", null, null, null);
        Long failed = persistRoutine("실패 루틴", "DAILY", null, null, null);
        Long noLog = persistRoutine("로그 없는 루틴", "DAILY", null, null, null);
        backdateCreatedAt(done, 90);
        backdateCreatedAt(failed, 90);
        backdateCreatedAt(noLog, 90);
        persistCompletedLog(done, PAST);
        persistFailedLog(failed, PAST);
        persistFailedLog(failed, PAST.minusDays(1));

        CalendarMonthResponse response = service.month(userId, PAST_MONTH);

        // COMPLETED + FAILED 합산, 로그 없는 루틴은 제외
        assertThat(countOn(response, PAST).routineCount()).isEqualTo(2);
        assertThat(countOn(response, PAST).routineCompletedCount()).isEqualTo(1);
        assertThat(countOn(response, PAST.minusDays(1)).routineCount()).isEqualTo(1);
        assertThat(countOn(response, PAST.minusDays(1)).routineCompletedCount()).isZero();
        assertThat(countOn(response, PAST.minusDays(2)).routineCount()).isZero();
    }

    @Test
    void 과거_삭제된_루틴의_로그도_센다() {
        Long routineId = persistRoutine("삭제될 루틴", "DAILY", null, null, null);
        backdateCreatedAt(routineId, 90);
        persistFailedLog(routineId, PAST);
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routine.softDelete(Instant.now());
        routineRepository.save(routine);
        em.flush();
        em.clear();

        assertThat(countOn(service.month(userId, PAST_MONTH), PAST).routineCount()).isEqualTo(1);
    }

    // --- 어제: 그날 유효했던 버전으로 재계산 ---

    @Test
    void 어제는_FAIL_로그가_아직_없어도_그날_유효했던_대상_루틴을_센다() {
        Long routineId = persistRoutine("어제 미완료 루틴", "DAILY", null, null, null);
        backdateCreatedAt(routineId, 20);

        CalendarMonthResponse response = service.month(userId, YearMonth.from(YESTERDAY));

        assertThat(countOn(response, YESTERDAY).routineCount()).isEqualTo(1);
        assertThat(countOn(response, YESTERDAY).routineCompletedCount()).isZero();
    }

    @Test
    void 어제_완료한_뒤_버전이_분기돼도_계보_기준_한_건으로_센다() {
        Long routineId = persistRoutine("분기 루틴", "DAILY", null, null, null);
        backdateCreatedAt(routineId, 20);
        persistCompletedLog(routineId, YESTERDAY);

        RoutineService routineService = new RoutineService(routineRepository, categoryRepository,
                userRepository, new HouseLinkValidator(houseRepository, houseMissionRepository,
                houseMemberRepository));
        routineService.update(userId, routineId, new RoutineUpdateRequest(null, null, null,
                "WEEKLY", new RepeatDays(List.of(weekdayToken(YESTERDAY))), null, null, null));
        em.flush();
        em.clear();
        backdateVersionBranch(routineId);

        CalendarMonthResponse response = service.month(userId, YearMonth.from(YESTERDAY));

        assertThat(countOn(response, YESTERDAY).routineCount()).isEqualTo(1);
        assertThat(countOn(response, YESTERDAY).routineCompletedCount()).isEqualTo(1);
        assertMatchesDay(YESTERDAY);
    }

    // --- 투두 ---

    @Test
    void 투두는_마감일별로_세고_삭제된_투두와_마감일_없는_투두는_제외한다() {
        persistTodo("오늘 마감 1", TODAY);
        persistTodo("오늘 마감 2", TODAY);
        persistCompletedTodo("오늘 마감 완료", TODAY);
        persistTodo("내일 마감", TODAY.plusDays(1));
        persistTodo("마감 없음", null);
        Todo deleted = todoRepository.save(Todo.create(user, null, "삭제된 투두", null, TODAY, null));
        deleted.softDelete(Instant.now());
        todoRepository.save(deleted);

        CalendarMonthResponse response = service.month(userId, YearMonth.from(TODAY));

        // 완료·미완료 합산 3, 삭제 제외
        assertThat(countOn(response, TODAY).todoCount()).isEqualTo(3);
        assertThat(countOn(response, TODAY).todoCompletedCount()).isEqualTo(1);
        if (YearMonth.from(TODAY.plusDays(1)).equals(YearMonth.from(TODAY))) {
            assertThat(countOn(response, TODAY.plusDays(1)).todoCount()).isEqualTo(1);
        }
    }

    @Test
    void 타인_소유_루틴과_투두는_세지_않는다() {
        User other = userRepository.save(User.signUp());
        routineRepository.save(Routine.create(other, null, "남의 루틴", AuthType.CHECK,
                "DAILY", null, null, null, null));
        todoRepository.save(Todo.create(other, null, "남의 투두", null, TODAY, null));

        CalendarMonthResponse response = service.month(userId, YearMonth.from(TODAY));

        assertThat(response.days()).allSatisfy(day -> {
            assertThat(day.routineCount()).isZero();
            assertThat(day.todoCount()).isZero();
        });
    }

    // --- day() 와의 정합 ---

    @Test
    void 월별_개수는_같은_날짜의_day_응답_건수와_일치한다() {
        Long daily = persistRoutine("매일", "DAILY", null, null, null);
        Long weekly = persistRoutine("주간", "WEEKLY",
                "{\"daysOfWeek\":[\"" + weekdayToken(YESTERDAY) + "\"]}", null, null);
        backdateCreatedAt(daily, 20);
        backdateCreatedAt(weekly, 20);
        // 그저께: 배치가 다 씀 / 어제: 완료 하나만 기록된 상태 / 오늘: 완료 하나
        LocalDate dayBeforeYesterday = TODAY.minusDays(2);
        persistCompletedLog(daily, dayBeforeYesterday);
        persistCompletedLog(daily, YESTERDAY);
        persistCompletedLog(daily, TODAY);
        persistTodo("그저께 투두", dayBeforeYesterday);
        persistTodo("어제 투두", YESTERDAY);
        persistCompletedTodo("오늘 완료 투두", TODAY);
        persistTodo("오늘 투두", TODAY);
        persistTodo("내일 투두", TODAY.plusDays(1));

        for (LocalDate date : List.of(dayBeforeYesterday, YESTERDAY, TODAY, TODAY.plusDays(1))) {
            assertMatchesDay(date);
        }
    }

    @Test
    void 오늘_완료_로그가_있어도_일별_표시_대상에서_빠진_루틴은_완료수에_포함하지_않는다() {
        Long visible = persistRoutine("오늘 완료", "DAILY", null, null, null);
        Long notScheduled = persistRoutine("내일 시작", "DAILY", null, TODAY.plusDays(1), null);
        Long deleted = persistRoutine("삭제된 완료", "DAILY", null, null, null);
        persistCompletedLog(visible, TODAY);
        persistCompletedLog(notScheduled, TODAY);
        persistCompletedLog(deleted, TODAY);
        routineRepository.findById(deleted).orElseThrow().softDelete(Instant.now());

        CalendarDayCount count = countOn(service.month(userId, YearMonth.from(TODAY)), TODAY);

        assertThat(count.routineCount()).isEqualTo(1);
        assertThat(count.routineCompletedCount()).isEqualTo(1);
        assertMatchesDay(TODAY);
    }

    @Test
    void 과거_삭제된_루틴의_완료_로그는_남고_타인_로그는_제외한다() {
        Long ownRoutine = persistRoutine("삭제된 내 루틴", "DAILY", null, null, null);
        persistCompletedLog(ownRoutine, PAST);
        routineRepository.findById(ownRoutine).orElseThrow().softDelete(Instant.now());
        User other = userRepository.save(User.signUp());
        Routine otherRoutine = routineRepository.save(Routine.create(other, null, "타인 루틴",
                AuthType.CHECK, "DAILY", null, null, null, null));
        routineLogRepository.save(RoutineLog.complete(otherRoutine, PAST, Instant.now(), CurrencyType.COIN, 0));
        routineLogRepository.save(RoutineLog.complete(otherRoutine, YESTERDAY, Instant.now(), CurrencyType.COIN, 0));
        routineLogRepository.save(RoutineLog.complete(otherRoutine, TODAY, Instant.now(), CurrencyType.COIN, 0));
        Todo otherTodo = todoRepository.save(Todo.create(other, null, "타인 투두", null, PAST, null));
        otherTodo.complete(CurrencyType.COIN, 0, Instant.now());

        CalendarDayCount count = countOn(service.month(userId, PAST_MONTH), PAST);

        assertThat(count.routineCount()).isEqualTo(1);
        assertThat(count.routineCompletedCount()).isEqualTo(1);
        assertThat(count.todoCount()).isZero();
        assertThat(count.todoCompletedCount()).isZero();
        assertMatchesDay(PAST);
        for (LocalDate date : List.of(YESTERDAY, TODAY)) {
            CalendarDayCount recent = countOn(service.month(userId, YearMonth.from(date)), date);
            assertThat(recent.routineCount()).isZero();
            assertThat(recent.routineCompletedCount()).isZero();
        }
    }

    @Test
    void 완료한_투두도_마감일이_없거나_삭제됐으면_집계에서_제외한다() {
        persistCompletedTodo("유효한 완료", TODAY);
        persistCompletedTodo("마감일 없는 완료", null);
        Todo deleted = todoRepository.save(Todo.create(user, null, "삭제된 완료", null, TODAY, null));
        deleted.complete(CurrencyType.COIN, 0, Instant.now());
        deleted.softDelete(Instant.now());

        CalendarDayCount count = countOn(service.month(userId, YearMonth.from(TODAY)), TODAY);

        assertThat(count.todoCount()).isEqualTo(1);
        assertThat(count.todoCompletedCount()).isEqualTo(1);
        assertMatchesDay(TODAY);
    }

    @Test
    void 완료와_취소를_반복해도_전체수는_유지되고_완료수만_변한다() {
        Long routineId = persistRoutine("기록 보존", "DAILY", null, null, null);
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        RoutineLog log = routineLogRepository.save(RoutineLog.fail(routine, PAST));
        Todo todo = todoRepository.save(Todo.create(user, null, "과거 투두", null, PAST, null));

        assertCompletionCounts(PAST, 0, 0);
        log.completeFromFailed(Instant.now(), CurrencyType.COIN);
        todo.complete(CurrencyType.COIN, 0, Instant.now());
        assertCompletionCounts(PAST, 1, 1);
        log.revertToFailed();
        todo.cancelComplete();
        assertCompletionCounts(PAST, 0, 0);
        log.completeFromFailed(Instant.now(), CurrencyType.COIN);
        todo.complete(CurrencyType.COIN, 0, Instant.now());
        assertCompletionCounts(PAST, 1, 1);
    }

    @Test
    void 월말_KST_자정_전후에도_일별과_월별의_완료수와_대상수가_같다() {
        LocalDate nextMonth = YearMonth.from(TODAY).plusMonths(1).atDay(1);
        LocalDate target = nextMonth.minusDays(1);
        Instant midnight = nextMonth.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Long done = persistRoutine("월말 완료", "DAILY", null, null, null);
        persistRoutine("월말 미완료", "DAILY", null, null, null);
        persistCompletedLog(done, target);
        persistCompletedTodo("월말 투두", target);

        // UTC Clock을 주입해도 KST 기준 오늘·어제 구간을 판정해야 함
        for (Instant instant : List.of(midnight.minusSeconds(1), midnight)) {
            service = new CalendarService(routineRepository, routineLogRepository, todoRepository,
                    new DailyAgendaAssembler(), Clock.fixed(instant, ZoneOffset.UTC));
            CalendarDayCount count = countOn(service.month(userId, YearMonth.from(target)), target);
            assertThat(count.routineCount()).isEqualTo(2);
            assertThat(count.routineCompletedCount()).isEqualTo(1);
            assertThat(count.todoCompletedCount()).isEqualTo(1);
            assertMatchesDay(target);
        }
    }

    @Test
    void 월_조회는_날짜마다_상세를_반복_조회하지_않고_최대_6번의_쿼리로_집계한다() {
        Long routineId = persistRoutine("매일", "DAILY", null, null, null);
        backdateCreatedAt(routineId, 20);
        persistCompletedLog(routineId, TODAY);
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            CalendarMonthResponse response = service.month(userId, YearMonth.from(TODAY));

            assertThat(response.days()).hasSize(YearMonth.from(TODAY).lengthOfMonth());
            assertThat(countOn(response, TODAY).routineCompletedCount()).isEqualTo(1);
            assertThat(statistics.getPrepareStatementCount()).isBetween(1L, 6L);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    private void assertCompletionCounts(LocalDate date, int routines, int todos) {
        CalendarDayCount count = countOn(service.month(userId, YearMonth.from(date)), date);
        assertThat(count.routineCount()).isEqualTo(1);
        assertThat(count.todoCount()).isEqualTo(1);
        assertThat(count.routineCompletedCount()).isEqualTo(routines);
        assertThat(count.todoCompletedCount()).isEqualTo(todos);
        assertMatchesDay(date);
    }

    private void assertMatchesDay(LocalDate date) {
        CalendarDayResponse day = service.day(userId, date);
        CalendarDayCount count = countOn(service.month(userId, YearMonth.from(date)), date);
        assertThat(count.routineCount()).as("routine on %s", date)
                .isEqualTo(day.categories().stream().mapToInt(g -> g.routines().size()).sum());
        assertThat(count.todoCount()).as("todo on %s", date)
                .isEqualTo(day.categories().stream().mapToInt(g -> g.todos().size()).sum());
        assertThat(count.routineCompletedCount()).as("completed routine on %s", date)
                .isEqualTo(day.categories().stream().flatMap(g -> g.routines().stream())
                        .filter(TodayRoutineItem::completed).count());
        assertThat(count.todoCompletedCount()).as("completed todo on %s", date)
                .isEqualTo(day.categories().stream().flatMap(g -> g.todos().stream())
                        .filter(todo -> todo.status() == TodoStatus.COMPLETED).count());
        assertThat(count.routineCompletedCount() + count.todoCompletedCount())
                .isEqualTo(day.summary().completedCount());
    }

    private CalendarDayCount countOn(CalendarMonthResponse response, LocalDate date) {
        return response.days().stream()
                .filter(day -> day.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + date));
    }

    private String weekdayToken(LocalDate date) {
        return date.getDayOfWeek().name().substring(0, 3);
    }

    private Long persistRoutine(String title, String repeatType, String repeatDays,
                                LocalDate startsOn, LocalDate endsOn) {
        Routine saved = routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                repeatType, repeatDays, null, startsOn, endsOn));
        saved.assignOriginToSelf();
        return saved.getId();
    }

    private void persistCompletedLog(Long routineId, LocalDate date) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routineLogRepository.save(RoutineLog.complete(routine, date, Instant.now(),
                CurrencyType.COIN, 10));
    }

    private void persistFailedLog(Long routineId, LocalDate date) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routineLogRepository.save(RoutineLog.fail(routine, date));
    }

    private void persistTodo(String title, LocalDate dueDate) {
        todoRepository.save(Todo.create(user, null, title, null, dueDate, null));
    }

    private void persistCompletedTodo(String title, LocalDate dueDate) {
        Todo todo = Todo.create(user, null, title, null, dueDate, null);
        todo.complete(CurrencyType.COIN, 5, Instant.now());
        todoRepository.save(todo);
    }

    private void backdateVersionBranch(Long oldVersionId) {
        em.flush();
        em.createNativeQuery("update routines set deleted_at = deleted_at - interval 1 day"
                + " where id = " + oldVersionId).executeUpdate();
        em.createNativeQuery("update routines set created_at = created_at - interval 1 day"
                + " where origin_routine_id = " + oldVersionId + " and deleted_at is null")
                .executeUpdate();
        em.clear();
    }

    private void backdateCreatedAt(Long routineId, int days) {
        em.flush();
        em.createNativeQuery(
                "update routines set created_at = created_at - interval " + days
                        + " day where id = " + routineId).executeUpdate();
        em.clear();
    }
}
