package com.triples.rougether.userapi.category.web;

import com.triples.rougether.userapi.category.dto.CategoryCreateRequest;
import com.triples.rougether.userapi.category.dto.CategoryListResponse;
import com.triples.rougether.userapi.category.dto.CategoryResponse;
import com.triples.rougether.userapi.category.dto.CategoryUpdateRequest;
import com.triples.rougether.userapi.category.service.CategoryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Category", description = "루틴 카테고리 관련 API")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "내 카테고리 목록 조회", description = "로그인한 회원이 소유한 카테고리를 정렬 순서대로 반환합니다.")
    @GetMapping
    public CategoryListResponse list(@CurrentUser AuthUser authUser) {
        return categoryService.list(authUser.id());
    }

    @Operation(summary = "카테고리 생성", description = "로그인한 회원의 새 카테고리를 생성합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@CurrentUser AuthUser authUser,
                                   @Valid @RequestBody CategoryCreateRequest request) {
        return categoryService.create(authUser.id(), request);
    }

    @Operation(summary = "카테고리 수정", description = "소유한 카테고리의 속성을 수정합니다.")
    @PutMapping("/{id}")
    public CategoryResponse update(@CurrentUser AuthUser authUser,
                                   @Parameter(description = "카테고리 ID") @PathVariable Long id,
                                   @Valid @RequestBody CategoryUpdateRequest request) {
        return categoryService.update(authUser.id(), id, request);
    }

    @Operation(summary = "카테고리 삭제", description = "소유한 카테고리를 삭제합니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser authUser,
                       @Parameter(description = "카테고리 ID") @PathVariable Long id) {
        categoryService.delete(authUser.id(), id);
    }
}
