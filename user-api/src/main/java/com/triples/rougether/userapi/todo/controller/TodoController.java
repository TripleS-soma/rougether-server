package com.triples.rougether.userapi.todo.controller;

import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.todo.dto.TodoCompleteResponse;
import com.triples.rougether.userapi.todo.dto.TodoCreateRequest;
import com.triples.rougether.userapi.todo.dto.TodoListResponse;
import com.triples.rougether.userapi.todo.dto.TodoResponse;
import com.triples.rougether.userapi.todo.dto.TodoUpdateRequest;
import com.triples.rougether.userapi.todo.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

@Tag(name = "Todo", description = "투두 관련 API")
@RestController
@RequestMapping("/api/v1/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @Operation(summary = "내 투두 목록 조회",
            description = "로그인한 회원이 소유한 투두를 반환합니다. categoryId·status·dueDate로 필터링할 수 있습니다.")
    @GetMapping
    public TodoListResponse list(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "카테고리 ID 필터") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "상태 필터") @RequestParam(required = false) TodoStatus status,
            @Parameter(description = "마감일 필터(ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        return todoService.list(authUser.id(), categoryId, status, dueDate);
    }

    @Operation(summary = "투두 단건 조회", description = "소유한 투두 한 건을 반환합니다.")
    @GetMapping("/{id}")
    public TodoResponse get(@CurrentUser AuthUser authUser,
                            @Parameter(description = "투두 ID") @PathVariable Long id) {
        return todoService.get(authUser.id(), id);
    }

    @Operation(summary = "투두 등록", description = "로그인한 회원의 새 투두를 등록합니다. 상태는 PENDING으로 시작합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@CurrentUser AuthUser authUser,
                               @Valid @RequestBody TodoCreateRequest request) {
        return todoService.create(authUser.id(), request);
    }

    @Operation(summary = "투두 수정", description = "소유한 투두의 속성을 수정합니다. 지정하지 않은 필드는 변경하지 않습니다.")
    @PutMapping("/{id}")
    public TodoResponse update(@CurrentUser AuthUser authUser,
                               @Parameter(description = "투두 ID") @PathVariable Long id,
                               @Valid @RequestBody TodoUpdateRequest request) {
        return todoService.update(authUser.id(), id, request);
    }

    @Operation(summary = "투두 삭제", description = "소유한 투두를 삭제합니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser authUser,
                       @Parameter(description = "투두 ID") @PathVariable Long id) {
        todoService.delete(authUser.id(), id);
    }

    @Operation(summary = "투두 완료 체크", description = "투두를 완료 처리합니다. 코인 5를 지급합니다.")
    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public TodoCompleteResponse complete(@CurrentUser AuthUser authUser,
                                         @Parameter(description = "투두 ID") @PathVariable Long id) {
        return todoService.complete(authUser.id(), id);
    }

    @Operation(summary = "투두 완료 취소", description = "당일 완료를 취소합니다. 지급한 코인을 회수합니다.")
    @DeleteMapping("/{id}/complete")
    public TodoResponse cancelComplete(@CurrentUser AuthUser authUser,
                                       @Parameter(description = "투두 ID") @PathVariable Long id) {
        return todoService.cancelComplete(authUser.id(), id);
    }
}
