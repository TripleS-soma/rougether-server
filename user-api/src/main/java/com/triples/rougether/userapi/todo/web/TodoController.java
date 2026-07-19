package com.triples.rougether.userapi.todo.web;

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
            description = "로그인한 회원이 소유한 투두를 반환합니다. categoryId·status·dueDate로 필터링할 수 있습니다. "
                    + "categoryId는 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id를 사용합니다. "
                    + "마감일(dueDate) 오름차순, 같으면 id 오름차순으로 정렬합니다. "
                    + "미지정한 필터 조건은 적용하지 않으며(전체 반환), 삭제한 투두는 포함하지 않습니다.")
    @GetMapping
    public TodoListResponse list(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "카테고리 ID 필터. 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값. 미지정 시 전체 카테고리")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "상태 필터. 허용값: PENDING(대기), COMPLETED(완료). 미지정 시 전체 상태")
            @RequestParam(required = false) TodoStatus status,
            @Parameter(description = "마감일 필터(YYYY-MM-DD). 마감일이 이 날짜와 정확히 일치하는 투두만 반환. 미지정 시 전체")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        return todoService.list(authUser.id(), categoryId, status, dueDate);
    }

    @Operation(summary = "투두 단건 조회", description = "소유한 투두 한 건을 반환합니다.")
    @GetMapping("/{id}")
    public TodoResponse get(@CurrentUser AuthUser authUser,
                            @Parameter(description = "투두 ID. 내 투두 목록 조회(GET /api/v1/todos) 응답의 id 값") @PathVariable Long id) {
        return todoService.get(authUser.id(), id);
    }

    @Operation(summary = "투두 등록",
            description = "로그인한 회원의 새 투두를 등록합니다. 상태는 PENDING으로 시작합니다. "
                    + "categoryId를 지정하지 않으면 미분류로 등록되며, 소유한 카테고리만 지정할 수 있습니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@CurrentUser AuthUser authUser,
                               @Valid @RequestBody TodoCreateRequest request) {
        return todoService.create(authUser.id(), request);
    }

    @Operation(summary = "투두 수정",
            description = "소유한 투두의 속성을 수정합니다. 지정하지 않은(null) 필드는 변경하지 않으며, title은 공백이면 기존 값을 유지합니다. "
                    + "categoryId를 지정하면 소유한 해당 카테고리로 이동합니다(null이면 기존 카테고리 유지).")
    @PutMapping("/{id}")
    public TodoResponse update(@CurrentUser AuthUser authUser,
                               @Parameter(description = "투두 ID. 내 투두 목록 조회(GET /api/v1/todos) 응답의 id 값") @PathVariable Long id,
                               @Valid @RequestBody TodoUpdateRequest request) {
        return todoService.update(authUser.id(), id, request);
    }

    @Operation(summary = "투두 삭제",
            description = "소유한 투두를 삭제합니다. 삭제한 투두는 투두 목록·단건 조회·오늘 현황에서 더 이상 조회되지 않습니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser authUser,
                       @Parameter(description = "투두 ID. 내 투두 목록 조회(GET /api/v1/todos) 응답의 id 값") @PathVariable Long id) {
        todoService.delete(authUser.id(), id);
    }

    @Operation(summary = "투두 완료 체크",
            description = "투두를 완료 처리합니다. 마감일(dueDate)이 오늘(KST 기준)이거나 이미 지났거나 없는 투두만 완료할 수 있습니다. "
                    + "코인 5는 마감일이 오늘인 완료에만 지급하며(일일 상한 4건 적용), 마감일이 지났거나 없는 완료는 0코인입니다. "
                    + "PENDING 상태의 투두만 완료할 수 있습니다. 루틴 완료와 달리 스트릭에는 반영되지 않습니다.")
    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public TodoCompleteResponse complete(@CurrentUser AuthUser authUser,
                                         @Parameter(description = "투두 ID. 내 투두 목록 조회(GET /api/v1/todos) 응답의 id 값") @PathVariable Long id) {
        return todoService.complete(authUser.id(), id);
    }

    @Operation(summary = "투두 완료 취소",
            description = "완료를 취소합니다. 과거에 완료한 투두도 취소할 수 있습니다. "
                    + "완료 시 지급했던 코인을 회수하고(잔액이 부족해도 그대로 차감) "
                    + "상태를 PENDING으로 되돌리며 완료 시각·보상 정보를 초기화합니다.")
    @DeleteMapping("/{id}/complete")
    public TodoResponse cancelComplete(@CurrentUser AuthUser authUser,
                                       @Parameter(description = "투두 ID. 내 투두 목록 조회(GET /api/v1/todos) 응답의 id 값") @PathVariable Long id) {
        return todoService.cancelComplete(authUser.id(), id);
    }
}
