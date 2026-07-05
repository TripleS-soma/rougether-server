package com.triples.rougether.userapi.category.service;

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
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.dto.CategoryResponse;
import com.triples.rougether.userapi.category.dto.CategoryUpdateRequest;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class CategoryServiceIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private CategoryService categoryService;
    private Long userId;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(
                categoryRepository, routineRepository, userRepository);
        userId = userRepository.save(User.signUp()).getId();
    }

    @Test
    void 첫_카테고리는_sortOrder_0_visibility_기본_PRIVATE() {
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("운동", null, null, null, null));

        assertThat(created.sortOrder()).isEqualTo(0);
        assertThat(created.visibility()).isEqualTo(PrivacyScope.PRIVATE);
    }

    @Test
    void sortOrder_미지정시_기존_최대값_플러스1을_부여한다() {
        categoryService.create(userId, new CategoryCreateRequest("a", null, null, 5, null));

        CategoryResponse second = categoryService.create(userId,
                new CategoryCreateRequest("b", null, null, null, null));

        assertThat(second.sortOrder()).isEqualTo(6);
    }

    @Test
    void 명시한_sortOrder와_visibility는_그대로_저장된다() {
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("집공개", "#FFAA00", "icon/run", 3, PrivacyScope.HOUSE));

        assertThat(created.sortOrder()).isEqualTo(3);
        assertThat(created.visibility()).isEqualTo(PrivacyScope.HOUSE);
        assertThat(created.colorHex()).isEqualTo("#FFAA00");
        assertThat(created.iconKey()).isEqualTo("icon/run");
    }

    @Test
    void 목록은_본인것만_sortOrder_오름차순으로_준다() {
        Long other = userRepository.save(User.signUp()).getId();
        categoryService.create(userId, new CategoryCreateRequest("b", null, null, 2, null));
        categoryService.create(userId, new CategoryCreateRequest("a", null, null, 1, null));
        categoryService.create(other, new CategoryCreateRequest("남의것", null, null, 0, null));

        var items = categoryService.list(userId, false).items();

        assertThat(items).extracting(CategoryResponse::name).containsExactly("a", "b");
    }

    @Test
    void update_는_null_필드를_건드리지_않는다() {
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("원래", "#111111", "icon/a", 1, PrivacyScope.HOUSE));

        CategoryResponse updated = categoryService.update(userId, created.id(),
                new CategoryUpdateRequest("변경", null, null, null, null));

        assertThat(updated.name()).isEqualTo("변경");
        assertThat(updated.colorHex()).isEqualTo("#111111");
        assertThat(updated.iconKey()).isEqualTo("icon/a");
        assertThat(updated.sortOrder()).isEqualTo(1);
        assertThat(updated.visibility()).isEqualTo(PrivacyScope.HOUSE);
    }

    @Test
    void update_의_공백_name은_기존_이름을_덮어쓰지_않는다() {
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("원래이름", null, null, 0, null));

        CategoryResponse updated = categoryService.update(userId, created.id(),
                new CategoryUpdateRequest("   ", "#222222", null, null, null));

        assertThat(updated.name()).isEqualTo("원래이름");
        assertThat(updated.colorHex()).isEqualTo("#222222");
    }

    @Test
    void 타인_카테고리_수정_삭제는_CATEGORY_NOT_FOUND() {
        Long other = userRepository.save(User.signUp()).getId();
        CategoryResponse othersCategory = categoryService.create(other,
                new CategoryCreateRequest("남의것", null, null, null, null));

        assertThatThrownBy(() -> categoryService.update(userId, othersCategory.id(),
                new CategoryUpdateRequest("탈취", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);

        assertThatThrownBy(() -> categoryService.delete(userId, othersCategory.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void ACTIVE_루틴이_있으면_삭제가_CATEGORY_IN_USE로_차단된다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        persistRoutine(user, category, RoutineStatus.ACTIVE);
        em.flush();

        assertThatThrownBy(() -> categoryService.delete(userId, category.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_IN_USE);
        assertThat(CategoryErrorCode.CATEGORY_IN_USE.status()).isEqualTo(409);
        assertThat(CategoryErrorCode.CATEGORY_IN_USE.message())
                .isEqualTo("이 카테고리를 사용하는 루틴이 있어 삭제할 수 없습니다.");
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt())
                .isNull();
    }

    @Test
    void PENDING_투두만_있으면_삭제되고_투두의_category는_유지된다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long todoId = persistTodo(user, category);

        categoryService.delete(userId, category.getId());
        em.flush();
        em.clear();

        Category reloaded = categoryRepository.findById(category.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        Todo todo = todoRepository.findById(todoId).orElseThrow();
        assertThat(todo.getCategory()).isNotNull();
        assertThat(todo.getCategory().getId()).isEqualTo(category.getId());
    }

    @Test
    void 삭제된_루틴만_있으면_카테고리_삭제가_허용된다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long routineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        routineRepository.findById(routineId).orElseThrow().softDelete(java.time.Instant.now());
        em.flush();
        em.clear();

        categoryService.delete(userId, category.getId());
        em.flush();

        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt())
                .isNotNull();
    }

    @Test
    void 목록은_기본으로_삭제_카테고리를_제외하고_includeDeleted면_deleted_플래그와_함께_포함한다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category live = categoryRepository.save(
                Category.create(user, "활성", null, null, 0, PrivacyScope.PRIVATE));
        Category removed = categoryRepository.save(
                Category.create(user, "삭제됨", null, null, 1, PrivacyScope.PRIVATE));
        categoryService.delete(userId, removed.getId());
        em.flush();
        em.clear();

        var active = categoryService.list(userId, false).items();
        assertThat(active).extracting(CategoryResponse::id).containsExactly(live.getId());
        assertThat(active).extracting(CategoryResponse::deleted).containsExactly(false);

        var all = categoryService.list(userId, true).items();
        assertThat(all).extracting(CategoryResponse::id)
                .containsExactly(live.getId(), removed.getId());
        assertThat(all).extracting(CategoryResponse::deleted)
                .containsExactly(false, true);
    }

    private Long persistRoutine(User user, Category category, RoutineStatus status) {
        Routine routine = BeanUtils.instantiateClass(Routine.class);
        ReflectionTestUtils.setField(routine, "user", user);
        ReflectionTestUtils.setField(routine, "category", category);
        ReflectionTestUtils.setField(routine, "title", "아침 운동");
        ReflectionTestUtils.setField(routine, "authType", AuthType.CHECK);
        ReflectionTestUtils.setField(routine, "status", status);
        return routineRepository.save(routine).getId();
    }

    private Long persistTodo(User user, Category category) {
        Todo todo = BeanUtils.instantiateClass(Todo.class);
        ReflectionTestUtils.setField(todo, "user", user);
        ReflectionTestUtils.setField(todo, "category", category);
        ReflectionTestUtils.setField(todo, "title", "장보기");
        ReflectionTestUtils.setField(todo, "status", TodoStatus.PENDING);
        return todoRepository.save(todo).getId();
    }
}
