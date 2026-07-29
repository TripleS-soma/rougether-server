package com.triples.rougether.userapi.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
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
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.PhotoVerificationRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.dto.CategoryDeleteMode;
import com.triples.rougether.userapi.category.dto.CategoryResponse;
import com.triples.rougether.userapi.category.dto.CategoryUpdateRequest;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

    // 서비스가 KST로 "오늘"을 판정하므로 테스트도 같은 기준을 씀
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private PhotoVerificationRepository photoVerificationRepository;
    @Autowired
    private StreakRepository streakRepository;
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

    private CategoryService categoryService;
    private Long userId;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository, routineRepository, todoRepository,
                routineLogRepository, photoVerificationRepository, userRepository,
                new HouseLinkValidator(houseRepository, houseMissionRepository, houseMemberRepository));
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

        assertThatThrownBy(() -> categoryService.delete(userId, othersCategory.id(), CategoryDeleteMode.UNASSIGN))
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

        assertThatThrownBy(() -> categoryService.delete(userId, category.getId(), CategoryDeleteMode.UNASSIGN))
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
    void PENDING_투두만_있으면_삭제되고_투두는_미분류로_전환된다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long todoId = persistTodo(user, category);

        categoryService.delete(userId, category.getId(), CategoryDeleteMode.UNASSIGN);
        em.flush();
        em.clear();

        Category reloaded = categoryRepository.findById(category.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        Todo todo = todoRepository.findById(todoId).orElseThrow();
        assertThat(todo.getCategory()).isNull();
        assertThat(todo.getDeletedAt()).isNull();
    }

    @Test
    void 삭제된_루틴만_있으면_카테고리_삭제가_허용된다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long routineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        routineRepository.findById(routineId).orElseThrow().softDelete(Instant.now());
        em.flush();
        em.clear();

        categoryService.delete(userId, category.getId(), CategoryDeleteMode.UNASSIGN);
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
        categoryService.delete(userId, removed.getId(), CategoryDeleteMode.UNASSIGN);
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

    @Test
    void UNASSIGN은_옛_버전_루틴과_살아있는_투두만_미분류로_바꾼다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long oldRoutineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        routineRepository.findById(oldRoutineId).orElseThrow().softDelete(Instant.now());
        Long liveTodoId = persistTodo(user, category);
        Long deletedTodoId = persistTodo(user, category);
        todoRepository.findById(deletedTodoId).orElseThrow().softDelete(Instant.now());
        em.flush();
        em.clear();

        categoryService.delete(userId, category.getId(), CategoryDeleteMode.UNASSIGN);
        em.flush();
        em.clear();

        assertThat(routineRepository.findById(oldRoutineId).orElseThrow().getCategory()).isNull();
        assertThat(todoRepository.findById(liveTodoId).orElseThrow().getCategory()).isNull();
        // soft delete된 투두는 과거 기록이라 카테고리 참조를 그대로 둠
        Todo deletedTodo = todoRepository.findById(deletedTodoId).orElseThrow();
        assertThat(deletedTodo.getCategory()).isNotNull();
        assertThat(deletedTodo.getCategory().getId()).isEqualTo(category.getId());
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt())
                .isNotNull();
    }

    @Test
    void UNASSIGN은_살아있는_루틴이_있으면_아무것도_바꾸지_않고_409로_막는다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long liveRoutineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        Long todoId = persistTodo(user, category);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> categoryService.delete(userId, category.getId(),
                CategoryDeleteMode.UNASSIGN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_IN_USE);

        em.clear();
        assertThat(routineRepository.findById(liveRoutineId).orElseThrow().getCategory()).isNotNull();
        assertThat(todoRepository.findById(todoId).orElseThrow().getCategory()).isNotNull();
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt())
                .isNull();
    }

    @Test
    void PURGE도_살아있는_루틴이_있으면_과거_기록을_지우지_않고_409로_막는다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long liveRoutineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        Long logId = persistRoutineLog(routineRepository.findById(liveRoutineId).orElseThrow());
        Long photoId = persistPhotoVerification(routineLogRepository.findById(logId).orElseThrow());
        Long todoId = persistTodo(user, category);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> categoryService.delete(userId, category.getId(),
                CategoryDeleteMode.PURGE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_IN_USE);

        em.clear();
        assertThat(photoVerificationRepository.findById(photoId)).isPresent();
        assertThat(routineLogRepository.findById(logId)).isPresent();
        assertThat(todoRepository.findById(todoId).orElseThrow().getDeletedAt()).isNull();
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt())
                .isNull();
    }

    @Test
    void PURGE는_사진인증과_로그를_지우고_살아있는_투두를_soft_delete한다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long routineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        routineRepository.findById(routineId).orElseThrow().softDelete(Instant.now());
        Long logId = persistRoutineLog(routineRepository.findById(routineId).orElseThrow());
        Long photoId = persistPhotoVerification(routineLogRepository.findById(logId).orElseThrow());
        Long liveTodoId = persistTodo(user, category);
        Long deletedTodoId = persistTodo(user, category);
        Instant alreadyDeletedAt = Instant.now().minusSeconds(3600);
        todoRepository.findById(deletedTodoId).orElseThrow().softDelete(alreadyDeletedAt);
        // 다른 카테고리 루틴의 로그는 남아야 함
        Category other = categoryRepository.save(
                Category.create(user, "무관", null, null, 1, PrivacyScope.PRIVATE));
        Long otherRoutineId = persistRoutine(user, other, RoutineStatus.ACTIVE);
        Long otherLogId = persistRoutineLog(routineRepository.findById(otherRoutineId).orElseThrow());
        Streak streak = streakRepository.save(Streak.start(user, TODAY));
        Long streakId = streak.getId();
        int streakCount = streak.getCurrentCount();
        em.flush();
        em.clear();

        categoryService.delete(userId, category.getId(), CategoryDeleteMode.PURGE);
        em.flush();
        em.clear();

        assertThat(photoVerificationRepository.findById(photoId)).isEmpty();
        assertThat(routineLogRepository.findById(logId)).isEmpty();
        assertThat(routineLogRepository.findById(otherLogId)).isPresent();
        assertThat(todoRepository.findById(liveTodoId).orElseThrow().getDeletedAt()).isNotNull();
        // 이미 삭제된 투두의 deletedAt은 덮어쓰지 않음
        assertThat(todoRepository.findById(deletedTodoId).orElseThrow().getDeletedAt())
                .isCloseTo(alreadyDeletedAt, within(1, ChronoUnit.SECONDS));
        // 루틴 row는 유지, 카테고리 참조도 그대로(PURGE는 미분류 전환을 하지 않음)
        Routine purgedRoutine = routineRepository.findById(routineId).orElseThrow();
        assertThat(purgedRoutine.getCategory().getId()).isEqualTo(category.getId());
        // Streak은 유저 단위 롤업이라 재계산 대상이 아님
        Streak reloadedStreak = streakRepository.findById(streakId).orElseThrow();
        assertThat(reloadedStreak.getCurrentCount()).isEqualTo(streakCount);
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt())
                .isNotNull();
    }

    // 일일 상한(DailyRewardService)이 오늘자 지급 log 수로 판정되므로, 지갑 회수 없이 지우면
    // 지급 슬롯이 부당 복구돼 같은 날 재완료로 코인을 또 받을 수 있음
    @Test
    void PURGE는_오늘_지급된_log는_남기고_오늘이라도_미지급이면_지운다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category category = categoryRepository.save(
                Category.create(user, "삭제대상", null, null, 0, PrivacyScope.PRIVATE));
        Long rewardedRoutineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        Long unrewardedRoutineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        Long pastRoutineId = persistRoutine(user, category, RoutineStatus.ACTIVE);
        routineRepository.findById(rewardedRoutineId).orElseThrow().softDelete(Instant.now());
        routineRepository.findById(unrewardedRoutineId).orElseThrow().softDelete(Instant.now());
        routineRepository.findById(pastRoutineId).orElseThrow().softDelete(Instant.now());
        Long rewardedLogId = persistRoutineLog(
                routineRepository.findById(rewardedRoutineId).orElseThrow(), TODAY, 10);
        Long rewardedPhotoId = persistPhotoVerification(
                routineLogRepository.findById(rewardedLogId).orElseThrow());
        // 상한 초과분은 reward 0으로 기록됨 — 지워도 지급 슬롯이 복구되지 않으므로 삭제 대상
        Long unrewardedLogId = persistRoutineLog(
                routineRepository.findById(unrewardedRoutineId).orElseThrow(), TODAY, 0);
        // 과거 완료는 항상 reward 0이라 어느 날의 상한에도 잡히지 않음
        Long pastLogId = persistRoutineLog(
                routineRepository.findById(pastRoutineId).orElseThrow(), TODAY.minusDays(1), 10);
        em.flush();
        em.clear();

        categoryService.delete(userId, category.getId(), CategoryDeleteMode.PURGE);
        em.flush();
        em.clear();

        assertThat(routineLogRepository.findById(rewardedLogId)).isPresent();
        // 남긴 log의 사진도 함께 남아야 함(제외 조건이 두 쿼리에서 어긋나면 FK가 깨짐)
        assertThat(photoVerificationRepository.findById(rewardedPhotoId)).isPresent();
        assertThat(routineLogRepository.findById(unrewardedLogId)).isEmpty();
        assertThat(routineLogRepository.findById(pastLogId)).isEmpty();
    }

    // 스케줄 수정으로 버전이 분기되면 옛 버전은 옛 카테고리에 soft delete로 남음.
    // 그 카테고리를 PURGE할 때 옛 버전 log를 지우면 지금도 쓰는 루틴의 기록이 사라지고,
    // 계보 기준 중복 완료 가드(findByLineageAndDateAndStatus)까지 비어 같은 날 재완료가 됨
    @Test
    void PURGE는_계보에_살아있는_버전이_있으면_그_루틴의_log를_남긴다() {
        User user = userRepository.findById(userId).orElseThrow();
        Category oldCategory = categoryRepository.save(
                Category.create(user, "운동", null, null, 0, PrivacyScope.PRIVATE));
        Category newCategory = categoryRepository.save(
                Category.create(user, "건강", null, null, 1, PrivacyScope.PRIVATE));
        // 옛 버전: 운동 카테고리에 남아 닫힘. 계보 루트라 origin = 자기 id
        Long oldVersionId = persistRoutine(user, oldCategory, RoutineStatus.ACTIVE);
        Routine oldVersion = routineRepository.findById(oldVersionId).orElseThrow();
        ReflectionTestUtils.setField(oldVersion, "originRoutineId", oldVersionId);
        oldVersion.softDelete(Instant.now());
        // 새 버전: 건강 카테고리로 옮겨가 살아있음. 같은 계보를 승계
        Long newVersionId = persistRoutine(user, newCategory, RoutineStatus.ACTIVE);
        ReflectionTestUtils.setField(
                routineRepository.findById(newVersionId).orElseThrow(), "originRoutineId", oldVersionId);
        Long lineageLogId = persistRoutineLog(oldVersion, TODAY.minusDays(5), 0);
        Long lineagePhotoId = persistPhotoVerification(
                routineLogRepository.findById(lineageLogId).orElseThrow());
        // 계보가 통째로 닫힌 루틴은 정상 삭제 대상
        Long orphanRoutineId = persistRoutine(user, oldCategory, RoutineStatus.ACTIVE);
        Routine orphan = routineRepository.findById(orphanRoutineId).orElseThrow();
        ReflectionTestUtils.setField(orphan, "originRoutineId", orphanRoutineId);
        orphan.softDelete(Instant.now());
        Long orphanLogId = persistRoutineLog(orphan, TODAY.minusDays(5), 0);
        em.flush();
        em.clear();

        categoryService.delete(userId, oldCategory.getId(), CategoryDeleteMode.PURGE);
        em.flush();
        em.clear();

        assertThat(routineLogRepository.findById(lineageLogId)).isPresent();
        assertThat(photoVerificationRepository.findById(lineagePhotoId)).isPresent();
        assertThat(routineLogRepository.findById(orphanLogId)).isEmpty();
    }

    // 기본은 과거·미지급 log — PURGE의 "오늘자 지급분 제외" 조건에 걸리지 않는 순수 삭제 대상
    private Long persistRoutineLog(Routine routine) {
        return persistRoutineLog(routine, TODAY.minusDays(3), 0);
    }

    private Long persistRoutineLog(Routine routine, LocalDate routineDate, int reward) {
        RoutineLog log = RoutineLog.complete(routine, routineDate, Instant.now(),
                CurrencyType.COIN, reward);
        return routineLogRepository.save(log).getId();
    }

    private Long persistPhotoVerification(RoutineLog routineLog) {
        PhotoVerification photo = BeanUtils.instantiateClass(PhotoVerification.class);
        ReflectionTestUtils.setField(photo, "routineLog", routineLog);
        ReflectionTestUtils.setField(photo, "storageKey", "routine/photo.jpg");
        ReflectionTestUtils.setField(photo, "privacyScope", PrivacyScope.PRIVATE);
        ReflectionTestUtils.setField(photo, "aiReviewStatus", AiReviewStatus.PENDING);
        ReflectionTestUtils.setField(photo, "uploadedAt", Instant.now());
        return photoVerificationRepository.save(photo).getId();
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

    // --- 집 연동(houseId) ---

    @Test
    void 생성_때_연동한_집_id가_저장되고_응답에_내려간다() {
        Long houseId = persistHouseWithMe();

        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("우리집 미션", null, null, null, null, houseId));

        assertThat(created.houseId()).isEqualTo(houseId);
        assertThat(categoryRepository.findById(created.id()).orElseThrow().getHouseId())
                .isEqualTo(houseId);
        // 연동 없이 생성하면 null
        CategoryResponse plain = categoryService.create(userId,
                new CategoryCreateRequest("운동", null, null, null, null));
        assertThat(plain.houseId()).isNull();
    }

    @Test
    void 내가_속하지_않은_집으로는_연동할_수_없다() {
        User other = userRepository.save(User.signUp());
        Long othersHouse = houseRepository.save(House.create(other, "남의 집", null, null, 4,
                "CATLNKX1", Instant.now().plus(Duration.ofDays(7)))).getId();

        assertThatThrownBy(() -> categoryService.create(userId,
                new CategoryCreateRequest("우리집 미션", null, null, null, null, othersHouse)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER);
    }

    @Test
    void 수정_때_미지정이면_기존_연동을_유지하고_지정하면_연동을_설정한다() {
        Long houseId = persistHouseWithMe();
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("우리집 미션", null, null, null, null, houseId));

        // 이름만 바꾸는 수정(구버전 클라이언트 시나리오) — 연동 유지
        CategoryResponse renamed = categoryService.update(userId, created.id(),
                new CategoryUpdateRequest("바뀐 이름", null, null, null, null));
        assertThat(renamed.houseId()).isEqualTo(houseId);

        // 미연동 카테고리에 수정으로 연동 지정
        CategoryResponse plain = categoryService.create(userId,
                new CategoryCreateRequest("운동", null, null, null, null));
        CategoryResponse linked = categoryService.update(userId, plain.id(),
                new CategoryUpdateRequest(null, null, null, null, null, houseId));
        assertThat(linked.houseId()).isEqualTo(houseId);
    }

    @Test
    void 연동_해제는_카테고리를_남기고_연동만_지우며_멱등이다() {
        Long houseId = persistHouseWithMe();
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("우리집 미션", null, null, null, null, houseId));

        categoryService.unlinkHouse(userId, created.id());

        Category unlinked = categoryRepository.findById(created.id()).orElseThrow();
        assertThat(unlinked.getHouseId()).isNull();
        assertThat(unlinked.getDeletedAt()).isNull();
        // 이미 미연동이어도 성공(멱등)
        categoryService.unlinkHouse(userId, created.id());
    }

    @Test
    void 타인_카테고리의_연동_해제는_CATEGORY_NOT_FOUND() {
        Long houseId = persistHouseWithMe();
        CategoryResponse created = categoryService.create(userId,
                new CategoryCreateRequest("우리집 미션", null, null, null, null, houseId));
        Long other = userRepository.save(User.signUp()).getId();

        assertThatThrownBy(() -> categoryService.unlinkHouse(other, created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CategoryErrorCode.CATEGORY_NOT_FOUND);
    }

    // 내가 ACTIVE 구성원인 집 생성
    private Long persistHouseWithMe() {
        User me = userRepository.findById(userId).orElseThrow();
        House house = houseRepository.save(House.create(me, "연동 집", null, null, 4,
                "CATLNKM1", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, me, HouseMemberRole.OWNER));
        return house.getId();
    }
}
