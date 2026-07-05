package com.triples.rougether.userapi.category.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.dto.CategoryListResponse;
import com.triples.rougether.userapi.category.dto.CategoryResponse;
import com.triples.rougether.userapi.category.dto.CategoryUpdateRequest;
import com.triples.rougether.userapi.category.error.CategoryErrorCode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;

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
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long userId, Long categoryId, CategoryUpdateRequest request) {
        Category category = findOwned(userId, categoryId);
        category.update(request.name(), request.colorHex(), request.iconKey(),
                request.sortOrder(), request.visibility());
        return CategoryResponse.from(category);
    }

    @Transactional
    public void delete(Long userId, Long categoryId) {
        Category category = findOwned(userId, categoryId);
        // status 무관 살아있는 루틴만 삭제 차단함. 투두는 참조해도 삭제 허용
        if (routineRepository.existsByCategoryIdAndDeletedAtIsNull(categoryId)) {
            throw new BusinessException(CategoryErrorCode.CATEGORY_IN_USE);
        }
        category.softDelete(Instant.now());
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
