package com.triples.rougether.userapi.todo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import com.triples.rougether.userapi.routine.service.RoutineService;
import com.triples.rougether.userapi.todo.dto.TodoConvertRequest;
import com.triples.rougether.userapi.todo.error.TodoErrorCode;
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

// 투두 → 반복 루틴 전환. 루틴 생성과 투두 삭제가 한 묶음으로 일어나는지, 완료·타인·삭제된 투두와
// 루틴 검증 실패에서 투두가 그대로 남는지, 캘린더 외부 참조가 재임포트 중복을 계속 막는지를 실제 DB 로 본다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class TodoConversionServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private TodoRepository todoRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMissionRepository houseMissionRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;

    @PersistenceContext
    private EntityManager em;

    private TodoConversionService service;
    private User user;
    private Long userId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        RoutineService routineService = new RoutineService(routineRepository, categoryRepository, userRepository,
                new HouseLinkValidator(houseRepository, houseMissionRepository, houseMemberRepository));
        service = new TodoConversionService(todoRepository, userRepository, routineService);
        user = userRepository.save(User.signUp());
        userId = user.getId();
        today = LocalDate.now(KST);
    }

    @Test
    void 전환하면_요청대로_루틴을_만들고_투두는_삭제한다() {
        Category category = categoryRepository.save(Category.create(user, "운동", "#FF0000", null, 0, PrivacyScope.PUBLIC));
        Todo todo = todoRepository.save(Todo.create(user, null, "헬스장", null, today, null));

        RoutineResponse routine = service.convertToRoutine(userId, todo.getId(), weeklyRequest(category.getId()));

        assertThat(routine.title()).isEqualTo("헬스장 가기");
        assertThat(routine.categoryId()).isEqualTo(category.getId());
        assertThat(routine.repeatType()).isEqualTo("WEEKLY");
        assertThat(routine.scheduledTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(routine.startsOn()).isEqualTo(today);
        em.flush();
        em.clear();
        assertThat(routineRepository.findById(routine.id())).isPresent();
        assertThat(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), userId)).isEmpty();
        assertThat(todoRepository.findById(todo.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void 캘린더_투두를_전환해도_루틴에는_외부_참조를_옮기지_않고_같은_일정의_재임포트는_계속_막힌다() {
        Todo todo = todoRepository.save(Todo.createImported(user, null, "치과", null, today, null,
                "GOOGLE_CALENDAR", "evt-1_20261118"));

        RoutineResponse routine = service.convertToRoutine(userId, todo.getId(), weeklyRequest(null));

        assertThat(routine.externalSource()).isNull();
        assertThat(routine.externalId()).isNull();
        em.flush();
        // 지운 투두가 (user, source, externalId) 를 계속 쥐고 있어 같은 회차를 다시 가져오면 중복으로 거절된다
        assertThat(todoRepository.existsByUserIdAndExternalSourceAndExternalId(
                userId, "GOOGLE_CALENDAR", "evt-1_20261118")).isTrue();
    }

    @Test
    void 완료한_투두는_409로_거절하고_루틴을_만들지_않는다() {
        Todo todo = Todo.create(user, null, "장보기", null, today, null);
        todo.complete(CurrencyType.COIN, 10, 10, Instant.now());
        todoRepository.save(todo);
        long routinesBefore = routineRepository.count();

        assertThatThrownBy(() -> service.convertToRoutine(userId, todo.getId(), weeklyRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(TodoErrorCode.TODO_COMPLETED_NOT_CONVERTIBLE);
        assertThat(routineRepository.count()).isEqualTo(routinesBefore);
        assertThat(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), userId)).isPresent();
    }

    @Test
    void 타인의_투두는_404로_거절한다() {
        User other = userRepository.save(User.signUp());
        Todo todo = todoRepository.save(Todo.create(other, null, "남의 일", null, today, null));

        assertThatThrownBy(() -> service.convertToRoutine(userId, todo.getId(), weeklyRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
        assertThat(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), other.getId())).isPresent();
    }

    @Test
    void 이미_삭제한_투두는_404로_거절한다() {
        Todo todo = todoRepository.save(Todo.create(user, null, "지운 일", null, today, null));
        todo.softDelete(Instant.now());

        assertThatThrownBy(() -> service.convertToRoutine(userId, todo.getId(), weeklyRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
    }

    @Test
    void 반복_규칙이_틀리면_루틴_검증_오류를_그대로_내고_투두는_남긴다() {
        Todo todo = todoRepository.save(Todo.create(user, null, "독서", null, today, null));
        // 격주인데 시작일이 없음
        TodoConvertRequest invalid = new TodoConvertRequest("독서", null, AuthType.CHECK, "BIWEEKLY",
                new RepeatDays(List.of("MON")), null, null, null);

        assertThatThrownBy(() -> service.convertToRoutine(userId, todo.getId(), invalid))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RoutineErrorCode.BIWEEKLY_REQUIRES_STARTS_ON);
        assertThat(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), userId)).isPresent();
    }

    @Test
    void 남의_카테고리를_지정하면_404로_거절하고_투두는_남긴다() {
        User other = userRepository.save(User.signUp());
        Category foreign = categoryRepository.save(Category.create(other, "남의 것", "#000000", null, 0, PrivacyScope.PUBLIC));
        Todo todo = todoRepository.save(Todo.create(user, null, "산책", null, today, null));

        assertThatThrownBy(() -> service.convertToRoutine(userId, todo.getId(), weeklyRequest(foreign.getId())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
        assertThat(todoRepository.findByIdAndUserIdAndDeletedAtIsNull(todo.getId(), userId)).isPresent();
    }

    private TodoConvertRequest weeklyRequest(Long categoryId) {
        return new TodoConvertRequest("헬스장 가기", categoryId, AuthType.CHECK, "WEEKLY",
                new RepeatDays(List.of("MON", "WED")),
                LocalTime.of(7, 0), today, null);
    }
}
