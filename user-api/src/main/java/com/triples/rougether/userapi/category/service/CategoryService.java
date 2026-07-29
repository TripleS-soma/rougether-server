package com.triples.rougether.userapi.category.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.PhotoVerificationRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.dto.CategoryDeleteMode;
import com.triples.rougether.userapi.category.dto.CategoryListResponse;
import com.triples.rougether.userapi.category.dto.CategoryResponse;
import com.triples.rougether.userapi.category.dto.CategoryUpdateRequest;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CategoryRepository categoryRepository;
    private final RoutineRepository routineRepository;
    private final TodoRepository todoRepository;
    private final RoutineLogRepository routineLogRepository;
    private final PhotoVerificationRepository photoVerificationRepository;
    private final UserRepository userRepository;
    private final HouseLinkValidator houseLinkValidator;

    @Transactional(readOnly = true)
    public CategoryListResponse list(Long userId, boolean includeDeleted) {
        List<Category> categories = includeDeleted
                ? categoryRepository.findByUserIdOrderBySortOrderAsc(userId)
                : categoryRepository.findByUserIdAndDeletedAtIsNullOrderBySortOrderAsc(userId);
        List<CategoryResponse> items = categories.stream()
                .map(CategoryResponse::from)
                .toList();
        return new CategoryListResponse(items);
    }

    @Transactional
    public CategoryResponse create(Long userId, CategoryCreateRequest request) {
        User user = userRepository.getReferenceById(userId);
        int sortOrder = request.sortOrder() != null ? request.sortOrder() : nextSortOrder(userId);
        PrivacyScope visibility = request.visibility() != null ? request.visibility() : PrivacyScope.PRIVATE;
        Category category = Category.create(
                user, request.name(), request.colorHex(), request.iconKey(), sortOrder, visibility);
        if (request.houseId() != null) {
            houseLinkValidator.validateHouseLink(userId, request.houseId());
            category.linkHouse(request.houseId());
        }
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long userId, Long categoryId, CategoryUpdateRequest request) {
        Category category = findOwned(userId, categoryId);
        category.update(request.name(), request.colorHex(), request.iconKey(),
                request.sortOrder(), request.visibility());
        // 집 연동은 null=기존 유지 — 연동 사실을 모르는 구버전 클라이언트의 수정 요청이
        // 연동을 지우지 않도록 함. 해제는 전용 API(unlinkHouse)
        if (request.houseId() != null) {
            houseLinkValidator.validateHouseLink(userId, request.houseId());
            category.linkHouse(request.houseId());
        }
        return CategoryResponse.from(category);
    }

    // 집 연동 해제 - 수정 API 는 null=기존 유지 규칙이라 해제 수단을 별도 API 로 둔다. 이미 미연동이어도 성공(멱등).
    @Transactional
    public void unlinkHouse(Long userId, Long categoryId) {
        findOwned(userId, categoryId).unlinkHouse();
    }

    @Transactional
    public void delete(Long userId, Long categoryId, CategoryDeleteMode mode) {
        Category category = findOwned(userId, categoryId);
        // status 무관 살아있는 루틴만 삭제 차단함. 투두는 참조해도 삭제 허용
        if (routineRepository.existsByCategoryIdAndDeletedAtIsNull(categoryId)) {
            throw new BusinessException(CategoryErrorCode.CATEGORY_IN_USE);
        }
        Instant now = Instant.now();
        // 아래 bulk 쿼리가 영속성 컨텍스트를 비우므로(clearAutomatically) category가 detach되기 전에 먼저 변경함.
        // 첫 bulk 쿼리의 flushAutomatically가 이 변경을 같은 트랜잭션에서 내보냄
        category.softDelete(now);
        switch (mode) {
            case UNASSIGN -> {
                routineRepository.clearCategoryByCategoryId(categoryId);
                todoRepository.clearCategoryByCategoryId(categoryId);
            }
            // 사진 인증 → 로그 순서. routine_log_id가 non-null FK라 역순이면 제약에 걸림.
            // 두 쿼리는 같은 today를 받아야 남기는 대상이 일치함.
            // 계산 직후 자정을 넘겨도 오삭제가 없는 건 purge 대상이 "계보에 살아있는 버전이 없는 루틴"뿐이고
            // log는 살아있는 루틴에만 생기기 때문임 — 계보 조건을 완화하면 이 보장도 같이 깨짐
            case PURGE -> {
                LocalDate today = LocalDate.now(KST);
                photoVerificationRepository.deleteByCategoryId(categoryId, today);
                routineLogRepository.deleteByCategoryId(categoryId, today);
                todoRepository.softDeleteByCategoryId(categoryId, now);
            }
        }
    }

    private Category findOwned(Long userId, Long categoryId) {
        return categoryRepository.findByIdAndUserIdAndDeletedAtIsNull(categoryId, userId)
                .orElseThrow(() -> new BusinessException(CategoryErrorCode.CATEGORY_NOT_FOUND));
    }

    private int nextSortOrder(Long userId) {
        Integer max = categoryRepository.findMaxSortOrderByUserId(userId);
        return max == null ? 0 : max + 1;
    }
}
