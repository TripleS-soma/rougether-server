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
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

    // 2026-06-29는 월요일(MON)
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 29);

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

    @Test
    void DAILY_루틴은_매일_대상이다() {
        persistRoutine("매일 운동", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        assertThat(routineTitles(service.day(userId, MONDAY))).containsExactly("매일 운동");
    }

    @Test
    void WEEKLY_루틴은_해당_요일만_대상이고_다른_요일은_제외된다() {
        persistRoutine("월요일 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"MON\"]}", null, null, null, null);
        persistRoutine("화요일 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"TUE\"]}", null, null, null, null);

        assertThat(routineTitles(service.day(userId, MONDAY))).containsExactly("월요일 루틴");
    }

    @Test
    void 시작전이거나_종료후면_제외된다() {
        persistRoutine("아직 시작 안 함", RoutineStatus.ACTIVE, "DAILY", null, null,
                MONDAY.plusDays(1), null, null);
        persistRoutine("이미 종료됨", RoutineStatus.ACTIVE, "DAILY", null, null,
                null, MONDAY.minusDays(1), null);
        persistRoutine("기간 내", RoutineStatus.ACTIVE, "DAILY", null, null,
                MONDAY.minusDays(3), MONDAY.plusDays(3), null);

        assertThat(routineTitles(service.day(userId, MONDAY))).containsExactly("기간 내");
    }

    @Test
    void 그날_완료_log가_있으면_completed_true_없으면_false() {
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistCompletedLog(done);

        List<TodayRoutineItem> routines = service.day(userId, MONDAY).categories().get(0).routines();

        assertThat(routines).filteredOn(r -> r.title().equals("완료 루틴"))
                .extracting(TodayRoutineItem::completed).containsExactly(true);
        assertThat(routines).filteredOn(r -> r.title().equals("미완료 루틴"))
                .extracting(TodayRoutineItem::completed).containsExactly(false);
    }

    @Test
    void 투두는_마감일이_그날인_것만_노출되고_지난_마감과_미래_마감은_제외된다() {
        persistTodo("그날 마감", null, MONDAY);
        persistTodo("지난 마감", null, MONDAY.minusDays(2));
        persistTodo("미래 마감", null, MONDAY.plusDays(1));

        assertThat(todoTitles(service.day(userId, MONDAY))).containsExactly("그날 마감");
    }

    @Test
    void 카테고리별로_묶이고_미분류는_별도_그룹으로_맨_뒤에_온다() {
        Category category = persistCategory("운동");
        persistRoutine("분류 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, category);
        persistRoutine("미분류 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistTodo("분류 투두", category, MONDAY);
        persistTodo("미분류 투두", null, MONDAY);

        var groups = service.day(userId, MONDAY).categories();

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).categoryId()).isEqualTo(category.getId());
        assertThat(groups.get(0).name()).isEqualTo("운동");
        assertThat(groups.get(1).categoryId()).isNull();
        assertThat(groups.get(1).todos()).extracting(t -> t.title()).containsExactly("미분류 투두");
    }

    @Test
    void summary는_그날_대상_기준_완료_미완료_진행률을_계산한다() {
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistCompletedLog(done);
        persistCompletedTodo("완료 투두", null, MONDAY);
        persistTodo("미완료 투두", null, MONDAY);

        var summary = service.day(userId, MONDAY).summary();

        // 완료: 루틴1 + 투두1 = 2, 전체: 루틴2 + 투두2 = 4
        assertThat(summary.completedCount()).isEqualTo(2);
        assertThat(summary.remainingCount()).isEqualTo(2);
        assertThat(summary.progressRate()).isEqualTo(0.5);
    }

    @Test
    void 미래_날짜도_조회할_수_있다() {
        // 오늘(2026-07-05)보다 뒤인 날짜 — 날짜 제한 없이 그날 대상 루틴이 나와야 함
        LocalDate future = LocalDate.now().plusYears(1);
        persistRoutine("미래 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        CalendarDayResponse response = service.day(userId, future);

        assertThat(response.date()).isEqualTo(future);
        assertThat(routineTitles(response)).containsExactly("미래 루틴");
    }

    @Test
    void 투두의_완료_상태와_완료시각이_그대로_노출된다() {
        persistCompletedTodo("완료 투두", null, MONDAY);
        persistTodo("미완료 투두", null, MONDAY);

        var todos = service.day(userId, MONDAY).categories().get(0).todos();

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
        todoRepository.save(Todo.create(other, null, "남의 투두", null, MONDAY));

        CalendarDayResponse response = service.day(userId, MONDAY);

        assertThat(response.categories()).isEmpty();
        assertThat(response.summary().completedCount()).isZero();
        assertThat(response.summary().remainingCount()).isZero();
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

    private void persistCompletedLog(Long routineId) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routineLogRepository.save(RoutineLog.complete(routine, MONDAY, Instant.now(),
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
}
