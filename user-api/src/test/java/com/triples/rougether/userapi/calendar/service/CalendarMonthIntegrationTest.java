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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
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
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
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
                new DailyAgendaAssembler());
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
        assertThat(countOn(response, PAST.minusDays(1)).routineCount()).isEqualTo(1);
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
            CalendarDayResponse day = service.day(userId, date);
            CalendarDayCount count = countOn(service.month(userId, YearMonth.from(date)), date);

            assertThat(count.routineCount()).as("routine on %s", date)
                    .isEqualTo(day.categories().stream().mapToInt(g -> g.routines().size()).sum());
            assertThat(count.todoCount()).as("todo on %s", date)
                    .isEqualTo(day.categories().stream().mapToInt(g -> g.todos().size()).sum());
        }
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
