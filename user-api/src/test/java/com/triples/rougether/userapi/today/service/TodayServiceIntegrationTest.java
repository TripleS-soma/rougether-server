package com.triples.rougether.userapi.today.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.StreakStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
class TodayServiceIntegrationTest {

    // 2026-06-29는 월요일(MON)
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 29);

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

    private TodayService service;
    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        service = new TodayService(routineRepository, routineLogRepository, todoRepository,
                streakRepository, new DailyAgendaAssembler());
        user = userRepository.save(User.signUp());
        userId = user.getId();
    }

    @Test
    void DAILY_루틴은_매일_노출된다() {
        persistRoutine("매일 운동", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        assertThat(routineTitles(service.today(userId, MONDAY))).containsExactly("매일 운동");
    }

    @Test
    void WEEKLY_루틴은_해당_요일만_노출되고_다른_요일은_제외된다() {
        persistRoutine("월요일 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"MON\"]}", null, null, null, null);
        persistRoutine("화요일 루틴", RoutineStatus.ACTIVE, "WEEKLY",
                "{\"daysOfWeek\":[\"TUE\"]}", null, null, null, null);

        assertThat(routineTitles(service.today(userId, MONDAY))).containsExactly("월요일 루틴");
    }

    @Test
    void BIWEEKLY_루틴은_starts_on_기준_2주_간격의_해당_요일만_노출된다() {
        persistRoutine("이번주 격주", RoutineStatus.ACTIVE, "BIWEEKLY",
                "{\"daysOfWeek\":[\"MON\"]}", null, MONDAY, null, null);
        persistRoutine("지난주_시작_격주", RoutineStatus.ACTIVE, "BIWEEKLY",
                "{\"daysOfWeek\":[\"MON\"]}", null, MONDAY.minusDays(7), null, null);

        assertThat(routineTitles(service.today(userId, MONDAY))).containsExactly("이번주 격주");
    }

    @Test
    void MONTHLY_루틴은_dayOfMonth가_일치하는_날만_노출된다() {
        persistRoutine("이달 29일", RoutineStatus.ACTIVE, "MONTHLY",
                "{\"dayOfMonth\":29}", null, null, null, null);
        persistRoutine("이달 30일", RoutineStatus.ACTIVE, "MONTHLY",
                "{\"dayOfMonth\":30}", null, null, null, null);

        assertThat(routineTitles(service.today(userId, MONDAY))).containsExactly("이달 29일");
    }

    @Test
    void YEARLY_루틴은_month_day가_일치하는_날만_노출된다() {
        persistRoutine("생일", RoutineStatus.ACTIVE, "YEARLY",
                "{\"month\":6,\"day\":29}", null, null, null, null);
        persistRoutine("다른날", RoutineStatus.ACTIVE, "YEARLY",
                "{\"month\":6,\"day\":28}", null, null, null, null);

        assertThat(routineTitles(service.today(userId, MONDAY))).containsExactly("생일");
    }

    @Test
    void 시작전이거나_종료후면_제외된다() {
        persistRoutine("아직 시작 안 함", RoutineStatus.ACTIVE, "DAILY", null, null,
                MONDAY.plusDays(1), null, null);
        persistRoutine("이미 종료됨", RoutineStatus.ACTIVE, "DAILY", null, null,
                null, MONDAY.minusDays(1), null);
        persistRoutine("기간 내", RoutineStatus.ACTIVE, "DAILY", null, null,
                MONDAY.minusDays(3), MONDAY.plusDays(3), null);

        assertThat(routineTitles(service.today(userId, MONDAY))).containsExactly("기간 내");
    }

    @Test
    void 당일_완료_log가_있으면_completed_true() {
        Long routineId = persistRoutine("운동", RoutineStatus.ACTIVE, "DAILY",
                null, null, null, null, null);
        persistCompletedLog(routineId);

        TodayRoutineItem item = service.today(userId, MONDAY).categories().get(0).routines().get(0);

        assertThat(item.completed()).isTrue();
    }

    @Test
    void 마감일이_정확히_오늘인_투두만_노출되고_지난_마감과_미래_마감은_제외된다() {
        persistTodo("오늘 마감", null, MONDAY);
        persistTodo("지난 마감", null, MONDAY.minusDays(2));
        persistTodo("미래 마감", null, MONDAY.plusDays(1));

        assertThat(todoTitles(service.today(userId, MONDAY)))
                .containsExactly("오늘 마감");
    }

    @Test
    void 카테고리별로_묶이고_미분류는_별도_그룹으로_맨_뒤에_온다() {
        Category category = persistCategory("운동");
        persistRoutine("분류 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, category);
        persistRoutine("미분류 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistTodo("분류 투두", category, MONDAY);
        persistTodo("미분류 투두", null, MONDAY);

        var groups = service.today(userId, MONDAY).categories();

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).categoryId()).isEqualTo(category.getId());
        assertThat(groups.get(0).todos()).extracting(t -> t.title()).containsExactly("분류 투두");
        assertThat(groups.get(1).categoryId()).isNull();
        assertThat(groups.get(1).todos()).extracting(t -> t.title()).containsExactly("미분류 투두");
    }

    @Test
    void 루틴은_scheduled_time_시간순이고_null은_뒤로_정렬된다() {
        persistRoutine("미정", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistRoutine("아침", RoutineStatus.ACTIVE, "DAILY", null, LocalTime.of(7, 0), null, null, null);
        persistRoutine("저녁", RoutineStatus.ACTIVE, "DAILY", null, LocalTime.of(20, 0), null, null, null);

        assertThat(routineTitles(service.today(userId, MONDAY)))
                .containsExactly("아침", "저녁", "미정");
    }

    @Test
    void summary는_루틴과_투두_완료_미완료_진행률을_정확히_계산한다() {
        Long done = persistRoutine("완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistRoutine("미완료 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);
        persistCompletedLog(done);
        persistCompletedTodo("완료 투두", null, MONDAY);
        persistTodo("미완료 투두", null, MONDAY);

        var summary = service.today(userId, MONDAY).summary();

        // 완료: 루틴1 + 투두1 = 2, 전체: 루틴2 + 투두2 = 4
        assertThat(summary.completedCount()).isEqualTo(2);
        assertThat(summary.remainingCount()).isEqualTo(2);
        assertThat(summary.progressRate()).isEqualTo(0.5);
    }

    @Test
    void date가_없으면_KST_오늘_기준으로_조회한다() {
        // DAILY는 어떤 날이든 대상이라 오늘 기준 조회 경로를 결정적으로 검증함
        persistRoutine("매일 루틴", RoutineStatus.ACTIVE, "DAILY", null, null, null, null, null);

        TodayResponse response = service.today(userId);

        assertThat(response.date()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
        assertThat(routineTitles(response)).containsExactly("매일 루틴");
    }

    @Test
    void 대상이_없으면_progressRate는_0이다() {
        var summary = service.today(userId, MONDAY).summary();

        assertThat(summary.completedCount()).isZero();
        assertThat(summary.remainingCount()).isZero();
        assertThat(summary.progressRate()).isZero();
    }

    @Test
    void 스트릭이_없으면_0_null로_응답한다() {
        var streak = service.today(userId, MONDAY).streak();

        assertThat(streak.currentCount()).isZero();
        assertThat(streak.longestCount()).isZero();
        assertThat(streak.lastSuccessDate()).isNull();
    }

    @Test
    void 스트릭이_있으면_반영한다() {
        persistStreak(5, 9, MONDAY);

        var streak = service.today(userId, MONDAY).streak();

        assertThat(streak.currentCount()).isEqualTo(5);
        assertThat(streak.longestCount()).isEqualTo(9);
        assertThat(streak.lastSuccessDate()).isEqualTo(MONDAY);
    }

    private java.util.List<String> routineTitles(TodayResponse response) {
        return response.categories().stream()
                .flatMap(g -> g.routines().stream())
                .map(TodayRoutineItem::title)
                .toList();
    }

    private java.util.List<String> todoTitles(TodayResponse response) {
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

    private void persistStreak(int currentCount, int longestCount, LocalDate lastSuccessDate) {
        Streak streak = Streak.start(user, lastSuccessDate);
        ReflectionTestUtils.setField(streak, "currentCount", currentCount);
        ReflectionTestUtils.setField(streak, "longestCount", longestCount);
        ReflectionTestUtils.setField(streak, "status", StreakStatus.ACTIVE);
        streakRepository.save(streak);
    }
}
