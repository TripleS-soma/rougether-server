package com.triples.rougether.userapi.today.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.agenda.LiveRoutineSelector;
import com.triples.rougether.userapi.agenda.dto.AgendaRoutineItem;
import com.triples.rougether.userapi.calendar.dto.CalendarDayCount;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.calendar.service.CalendarService;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class LiveRoutineSelectionIntegrationTest {

    // 오늘 조회에는 날짜를 직접 전달하고 캘린더에는 같은 KST 날짜의 고정 시계를 주입함.
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 15);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;

    private TodayService todayService;
    private CalendarService calendarService;
    private User user;

    @BeforeEach
    void setUp() {
        DailyAgendaAssembler assembler = new DailyAgendaAssembler();
        todayService = new TodayService(routineRepository, routineLogRepository, todoRepository,
                streakRepository, assembler, new LiveRoutineSelector());
        calendarService = new CalendarService(routineRepository, routineLogRepository, todoRepository,
                assembler, new LiveRoutineSelector(), CLOCK);
        user = userRepository.save(User.signUp());
    }

    @Test
    void 세_조회에서_건너뜀은_계보로_완료는_현재_버전_id로_판정하고_다른_날에는_전파하지_않는다() {
        Routine skippedOriginal = daily("건너뛴 옛 버전");
        routineLogRepository.save(RoutineLog.skip(skippedOriginal, MONDAY));
        Routine skippedCurrent = replaceVersion(skippedOriginal, "건너뛴 새 버전");

        Routine completedOriginal = daily("완료한 옛 버전");
        complete(completedOriginal, MONDAY);
        Routine completedCurrent = replaceVersion(completedOriginal, "미완료 새 버전");

        Routine done = daily("현재 버전 완료");
        complete(done, MONDAY);
        Routine failed = daily("실패 로그가 있는 대상");
        routineLogRepository.save(RoutineLog.fail(failed, MONDAY));

        Routine deleted = daily("삭제된 완료");
        complete(deleted, MONDAY);
        deleted.softDelete(CLOCK.instant());
        Routine startsTomorrow = routine("내일 시작", "DAILY", null, MONDAY.plusDays(1), null);
        complete(startsTomorrow, MONDAY);

        // origin이 없는 기존 데이터도 자신의 id를 계보 키로 사용해야 함.
        Routine withoutOrigin = routineRepository.save(Routine.create(user, null, "계보 없는 건너뜀",
                AuthType.CHECK, "DAILY", null, null, null, null));
        routineLogRepository.save(RoutineLog.skip(withoutOrigin, MONDAY));
        flushAndClear();

        assertAllViews(MONDAY, Map.of(
                completedCurrent.getId(), false,
                done.getId(), true,
                failed.getId(), false));
        assertAllViews(MONDAY.plusDays(1), Map.of(
                skippedCurrent.getId(), false,
                completedCurrent.getId(), false,
                done.getId(), false,
                failed.getId(), false,
                startsTomorrow.getId(), false,
                withoutOrigin.getId(), false));
    }

    @Test
    void 세_조회에서_반복_규칙과_기간_경계를_동일하게_적용하고_월_로그를_날짜별로_판정한다() {
        Routine daily = routine("이틀만 매일", "DAILY", null, MONDAY, MONDAY.plusDays(1));
        Routine weekly = routine("매주 월요일", "WEEKLY", "{\"daysOfWeek\":[\"MON\"]}", null, null);
        Routine biweekly = routine("격주 월요일", "BIWEEKLY", "{\"daysOfWeek\":[\"MON\"]}", MONDAY, null);
        Routine monthly = routine("매달 15일", "MONTHLY", "{\"dayOfMonth\":15}", null, null);
        Routine yearly = routine("매년 6월 16일", "YEARLY", "{\"month\":6,\"day\":16}", null, null);
        complete(weekly, MONDAY);
        routineLogRepository.save(RoutineLog.skip(weekly, MONDAY.plusWeeks(1)));
        complete(biweekly, MONDAY.plusWeeks(2));
        flushAndClear();

        assertAllViews(MONDAY, Map.of(
                daily.getId(), false,
                weekly.getId(), true,
                biweekly.getId(), false,
                monthly.getId(), false));
        assertAllViews(MONDAY.plusDays(1), Map.of(
                daily.getId(), false,
                yearly.getId(), false));
        assertAllViews(MONDAY.plusWeeks(1), Map.of());
        assertAllViews(MONDAY.plusWeeks(2), Map.of(
                weekly.getId(), false,
                biweekly.getId(), true));
    }

    private void assertAllViews(LocalDate date, Map<Long, Boolean> expectedCompletions) {
        TodayResponse today = todayService.today(user.getId(), date);
        CalendarDayResponse day = calendarService.day(user.getId(), date);
        CalendarDayCount monthDay = calendarService.month(user.getId(), YearMonth.from(date))
                .days().stream()
                .filter(count -> count.date().equals(date))
                .findFirst().orElseThrow();
        List<AgendaRoutineItem> items = today.categories().stream()
                .flatMap(category -> category.routines().stream()).toList();
        int completedCount = (int) expectedCompletions.values().stream()
                .filter(Boolean::booleanValue).count();

        assertThat(items).extracting(AgendaRoutineItem::id)
                .as("%s 표시 대상", date)
                .containsExactlyInAnyOrderElementsOf(expectedCompletions.keySet());
        assertThat(items).allSatisfy(item -> assertThat(item.completed())
                .as("%s 루틴 %s 완료 여부", date, item.id())
                .isEqualTo(expectedCompletions.get(item.id())));
        assertThat(today.summary().completedCount()).isEqualTo(completedCount);
        assertThat(today.summary().remainingCount()).isEqualTo(expectedCompletions.size() - completedCount);
        assertThat(today.summary().progressRate()).isEqualTo(expectedCompletions.isEmpty()
                ? 0.0 : (double) completedCount / expectedCompletions.size());
        assertThat(day.categories()).as("%s 오늘과 캘린더 목록", date).isEqualTo(today.categories());
        assertThat(day.summary()).isEqualTo(today.summary());
        assertThat(monthDay.routineCount()).isEqualTo(expectedCompletions.size());
        assertThat(monthDay.routineCompletedCount()).isEqualTo(completedCount);
        assertThat(monthDay.todoCount()).isZero();
        assertThat(monthDay.todoCompletedCount()).isZero();
    }

    private Routine daily(String title) {
        return routine(title, "DAILY", null, null, null);
    }

    private Routine routine(String title, String repeatType, String repeatDays,
                            LocalDate startsOn, LocalDate endsOn) {
        Routine saved = routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                repeatType, repeatDays, null, startsOn, endsOn));
        saved.assignOriginToSelf();
        return saved;
    }

    private Routine replaceVersion(Routine original, String newTitle) {
        original.softDelete(CLOCK.instant());
        return routineRepository.save(original.copyAsNewVersion(null, newTitle, null,
                null, null, null, null, null));
    }

    private void complete(Routine routine, LocalDate date) {
        routineLogRepository.save(RoutineLog.complete(routine, date, CLOCK.instant(), CurrencyType.COIN, 0));
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
