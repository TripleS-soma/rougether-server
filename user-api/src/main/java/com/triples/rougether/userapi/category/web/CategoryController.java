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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Category", description = "루틴 카테고리 관련 API")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "내 카테고리 목록 조회",
            description = "로그인한 회원이 소유한 카테고리를 정렬 순서(sortOrder) 오름차순으로 반환합니다. "
                    + "기본적으로 삭제한 카테고리는 포함하지 않으며, includeDeleted=true이면 삭제한 카테고리도 함께 반환하고 각 항목의 deleted 플래그로 구분합니다.")
    @GetMapping
    public CategoryListResponse list(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "삭제한 카테고리 포함 여부. true면 활성 + 삭제 카테고리를 모두 반환")
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return categoryService.list(authUser.id(), includeDeleted);
    }

    @Operation(summary = "카테고리 생성",
            description = "로그인한 회원의 새 카테고리를 생성합니다. "
                    + "sortOrder를 지정하지 않으면 맨 뒤 순서(기존 최대 sortOrder + 1, 첫 카테고리는 0)가 부여되고, "
                    + "visibility를 지정하지 않으면 PRIVATE로 생성됩니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@CurrentUser AuthUser authUser,
                                   @Valid @RequestBody CategoryCreateRequest request) {
        return categoryService.create(authUser.id(), request);
    }

    @Operation(summary = "카테고리 수정",
            description = "소유한 카테고리의 속성을 수정합니다. 지정하지 않은(null) 필드는 변경하지 않으며, name은 공백이면 기존 값을 유지합니다.")
    @PutMapping("/{id}")
    public CategoryResponse update(@CurrentUser AuthUser authUser,
                                   @Parameter(description = "카테고리 ID. 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값") @PathVariable Long id,
                                   @Valid @RequestBody CategoryUpdateRequest request) {
        return categoryService.update(authUser.id(), id, request);
    }

    @Operation(summary = "카테고리 삭제",
            description = "소유한 카테고리를 삭제합니다. 이 카테고리를 사용하는 살아있는 루틴이 없을 때만 삭제할 수 있으며, "
                    + "투두만 참조하는 경우에는 삭제할 수 있습니다. 삭제 후에도 투두의 categoryId는 그대로 유지되며, "
                    + "삭제된 카테고리의 이름은 내 카테고리 목록 조회(GET /api/v1/categories?includeDeleted=true)로 조회합니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser authUser,
                       @Parameter(description = "카테고리 ID. 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값") @PathVariable Long id) {
        categoryService.delete(authUser.id(), id);
    }
}
