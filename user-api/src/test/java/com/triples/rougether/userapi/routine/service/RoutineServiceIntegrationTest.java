package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
class RoutineServiceIntegrationTest {

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private RoutineService routineService;
    private Long userId;

    @BeforeEach
    void setUp() {
        routineService = new RoutineService(routineRepository, categoryRepository, userRepository);
        userId = userRepository.save(User.signUp()).getId();
    }

    @Test
    void 등록하면_status가_ACTIVE로_주입된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("아침 운동", null, AuthType.CHECK, null, null, null, null, null));

        assertThat(created.status()).isEqualTo(RoutineStatus.ACTIVE);
    }

    @Test
    void categoryId_미지정이면_미분류로_등록된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("물 마시기", null, AuthType.CHECK, null, null, null, null, null));

        assertThat(created.categoryId()).isNull();
    }

    @Test
    void 본인_카테고리를_지정하면_연결된다() {
        Long categoryId = persistCategory(userId, "운동");

        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("아침 운동", categoryId, AuthType.PHOTO,
                        "WEEKLY", "{\"daysOfWeek\":[\"MON\"]}", LocalTime.of(7, 0), null, null));

        assertThat(created.categoryId()).isEqualTo(categoryId);
        assertThat(created.authType()).isEqualTo(AuthType.PHOTO);
        assertThat(created.repeatType()).isEqualTo("WEEKLY");
    }

    @Test
    void 타인_카테고리를_지정하면_CATEGORY_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        Long othersCategory = persistCategory(other, "남의것");

        assertThatThrownBy(() -> routineService.create(userId,
                new RoutineCreateRequest("탈취", othersCategory, AuthType.CHECK, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 타인_루틴_조회_수정_삭제는_ROUTINE_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        RoutineResponse othersRoutine = routineService.create(other,
                new RoutineCreateRequest("남의 루틴", null, AuthType.CHECK, null, null, null, null, null));

        assertThatThrownBy(() -> routineService.get(userId, othersRoutine.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
        assertThatThrownBy(() -> routineService.update(userId, othersRoutine.id(),
                new RoutineUpdateRequest("탈취", null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
        assertThatThrownBy(() -> routineService.delete(userId, othersRoutine.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
    }

    @Test
    void 수정_시_타인_카테고리를_지정하면_CATEGORY_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        Long othersCategory = persistCategory(other, "남의것");
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("원래", null, AuthType.CHECK, null, null, null, null, null));

        assertThatThrownBy(() -> routineService.update(userId, created.id(),
                new RoutineUpdateRequest(null, othersCategory, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 수정은_null_필드를_건드리지_않고_categoryId는_변경한다() {
        Long firstCategory = persistCategory(userId, "운동");
        Long secondCategory = persistCategory(userId, "공부");
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("원래", firstCategory, AuthType.CHECK,
                        "DAILY", null, LocalTime.of(8, 0), null, null));

        RoutineResponse updated = routineService.update(userId, created.id(),
                new RoutineUpdateRequest("변경됨", secondCategory, null, null, null, null, null, null));

        assertThat(updated.title()).isEqualTo("변경됨");
        assertThat(updated.categoryId()).isEqualTo(secondCategory);
        assertThat(updated.authType()).isEqualTo(AuthType.CHECK);
        assertThat(updated.repeatType()).isEqualTo("DAILY");
        assertThat(updated.scheduledTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void 삭제하면_soft_delete되고_목록에서_제외된다() {
        RoutineResponse created = routineService.create(userId,
                new RoutineCreateRequest("삭제대상", null, AuthType.CHECK, null, null, null, null, null));

        routineService.delete(userId, created.id());
        em.flush();
        em.clear();

        Routine reloaded = routineRepository.findById(created.id()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(routineService.list(userId, null, null).items()).isEmpty();
        assertThatThrownBy(() -> routineService.get(userId, created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
    }

    @Test
    void 목록은_categoryId와_status로_필터링한다() {
        Long category = persistCategory(userId, "운동");
        RoutineResponse inCategory = routineService.create(userId,
                new RoutineCreateRequest("분류됨", category, AuthType.CHECK, null, null,
                        LocalTime.of(6, 0), null, null));
        routineService.create(userId,
                new RoutineCreateRequest("미분류", null, AuthType.CHECK, null, null,
                        LocalTime.of(7, 0), null, null));
        Long pausedId = persistPausedRoutine(userId, "일시중지");

        assertThat(routineService.list(userId, category, null).items())
                .extracting(RoutineResponse::id).containsExactly(inCategory.id());
        assertThat(routineService.list(userId, null, RoutineStatus.PAUSED).items())
                .extracting(RoutineResponse::id).containsExactly(pausedId);
        assertThat(routineService.list(userId, null, RoutineStatus.ACTIVE).items()).hasSize(2);
        assertThat(routineService.list(userId, category, RoutineStatus.ACTIVE).items())
                .extracting(RoutineResponse::id).containsExactly(inCategory.id());
        assertThat(routineService.list(userId, category, RoutineStatus.PAUSED).items()).isEmpty();
        assertThat(routineService.list(userId, null, null).items()).hasSize(3);
    }

    private Long persistCategory(Long ownerId, String name) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        return categoryRepository.save(
                Category.create(owner, name, null, null, 0, PrivacyScope.PRIVATE)).getId();
    }

    private Long persistPausedRoutine(Long ownerId, String title) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        Routine routine = Routine.create(owner, null, title, AuthType.CHECK, null, null,
                LocalTime.of(9, 0), null, null);
        ReflectionTestUtils.setField(routine, "status", RoutineStatus.PAUSED);
        return routineRepository.save(routine).getId();
    }
}
