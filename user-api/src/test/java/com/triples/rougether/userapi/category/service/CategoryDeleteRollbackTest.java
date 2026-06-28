package com.triples.rougether.userapi.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class CategoryDeleteRollbackTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private TodoRepository todoRepository;

    private Long createdRoutineId;
    private Long createdCategoryId;
    private Long createdUserId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (createdRoutineId != null) {
            routineRepository.deleteById(createdRoutineId);
        }
        if (createdCategoryId != null) {
            categoryRepository.deleteById(createdCategoryId);
        }
        if (createdUserId != null) {
            userRepository.deleteById(createdUserId);
        }
    }

    @Test
    void todo_미분류_단계_실패시_soft_delete와_routine_미분류가_전부_롤백된다() {
        User user = userRepository.save(User.signUp());
        createdUserId = user.getId();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        createdCategoryId = category.getId();
        createdRoutineId = persistRoutine(user, category);

        doThrow(new RuntimeException("todo 미분류 실패"))
                .when(todoRepository).clearCategory(anyLong());

        assertThatThrownBy(() -> categoryService.delete(createdUserId, createdCategoryId))
                .isInstanceOf(RuntimeException.class);

        Category reloaded = categoryRepository.findById(createdCategoryId).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();
        Routine routine = routineRepository.findById(createdRoutineId).orElseThrow();
        assertThat(routine.getCategory()).isNotNull();
        assertThat(routine.getCategory().getId()).isEqualTo(createdCategoryId);
    }

    private Long persistRoutine(User user, Category category) {
        Routine routine = BeanUtils.instantiateClass(Routine.class);
        ReflectionTestUtils.setField(routine, "user", user);
        ReflectionTestUtils.setField(routine, "category", category);
        ReflectionTestUtils.setField(routine, "title", "아침 운동");
        ReflectionTestUtils.setField(routine, "authType", AuthType.CHECK);
        ReflectionTestUtils.setField(routine, "status", RoutineStatus.ACTIVE);
        return routineRepository.save(routine).getId();
    }
}
