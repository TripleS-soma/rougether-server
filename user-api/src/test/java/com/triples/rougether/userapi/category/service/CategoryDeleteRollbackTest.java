package com.triples.rougether.userapi.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AiReviewStatus;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PhotoVerification;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.PhotoVerificationRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.dto.CategoryDeleteMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

// PURGE는 사진 인증 hard delete → 로그 hard delete → 투두 soft delete → 카테고리 soft delete가
// 한 트랜잭션이라 중간 실패 시 지워진 과거 기록이 되살아나야 함(부분 커밋되면 복구 불가).
@SpringBootTest
class CategoryDeleteRollbackTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private PhotoVerificationRepository photoVerificationRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long createdCategoryId;
    private Long createdUserId;
    private Long createdRoutineId;
    private Long createdLogId;
    private Long createdPhotoId;
    private Long createdTodoId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함. FK 역순으로 지움
        if (createdPhotoId != null) {
            photoVerificationRepository.deleteById(createdPhotoId);
        }
        if (createdLogId != null) {
            routineLogRepository.deleteById(createdLogId);
        }
        if (createdTodoId != null) {
            todoRepository.deleteById(createdTodoId);
        }
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
    void 삭제_트랜잭션_도중_예외가_나면_soft_delete가_롤백된다() {
        User user = userRepository.save(User.signUp());
        createdUserId = user.getId();
        // 차단 루틴이 없어야 softDelete 경로에 진입함
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        createdCategoryId = category.getId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            categoryService.delete(createdUserId, createdCategoryId, CategoryDeleteMode.UNASSIGN);
            throw new RuntimeException("softDelete 이후 단계 실패");
        })).isInstanceOf(RuntimeException.class);

        Category reloaded = categoryRepository.findById(createdCategoryId).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    @Test
    void PURGE_도중_예외가_나면_hard_delete한_기록까지_전부_롤백된다() {
        User user = userRepository.save(User.signUp());
        createdUserId = user.getId();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        createdCategoryId = category.getId();
        Routine routine = BeanUtils.instantiateClass(Routine.class);
        ReflectionTestUtils.setField(routine, "user", user);
        ReflectionTestUtils.setField(routine, "category", category);
        ReflectionTestUtils.setField(routine, "title", "아침 운동");
        ReflectionTestUtils.setField(routine, "authType", AuthType.CHECK);
        ReflectionTestUtils.setField(routine, "status", RoutineStatus.ACTIVE);
        // 살아있는 루틴이면 CATEGORY_IN_USE로 막히므로 옛 버전으로 만듦
        ReflectionTestUtils.setField(routine, "deletedAt", Instant.now());
        createdRoutineId = routineRepository.save(routine).getId();
        // 과거·미지급 log여야 PURGE의 제외 조건에 걸리지 않아 실제로 삭제됨 —
        // 오늘자 지급 log면 애초에 안 지워져서 롤백 단언이 무의미해짐
        createdLogId = routineLogRepository.save(RoutineLog.complete(
                routine, LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(3), Instant.now(),
                CurrencyType.COIN, 0)).getId();
        PhotoVerification photo = BeanUtils.instantiateClass(PhotoVerification.class);
        ReflectionTestUtils.setField(photo, "routineLog",
                routineLogRepository.findById(createdLogId).orElseThrow());
        ReflectionTestUtils.setField(photo, "storageKey", "routine/photo.jpg");
        ReflectionTestUtils.setField(photo, "privacyScope", PrivacyScope.PRIVATE);
        ReflectionTestUtils.setField(photo, "aiReviewStatus", AiReviewStatus.PENDING);
        ReflectionTestUtils.setField(photo, "uploadedAt", Instant.now());
        createdPhotoId = photoVerificationRepository.save(photo).getId();
        Todo todo = BeanUtils.instantiateClass(Todo.class);
        ReflectionTestUtils.setField(todo, "user", user);
        ReflectionTestUtils.setField(todo, "category", category);
        ReflectionTestUtils.setField(todo, "title", "장보기");
        ReflectionTestUtils.setField(todo, "status", TodoStatus.PENDING);
        createdTodoId = todoRepository.save(todo).getId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            categoryService.delete(createdUserId, createdCategoryId, CategoryDeleteMode.PURGE);
            throw new RuntimeException("PURGE 이후 단계 실패");
        })).isInstanceOf(RuntimeException.class);

        assertThat(photoVerificationRepository.findById(createdPhotoId)).isPresent();
        assertThat(routineLogRepository.findById(createdLogId)).isPresent();
        assertThat(todoRepository.findById(createdTodoId).orElseThrow().getDeletedAt()).isNull();
        assertThat(categoryRepository.findById(createdCategoryId).orElseThrow().getDeletedAt())
                .isNull();
    }
}
