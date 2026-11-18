package com.triples.rougether.userapi.todo.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.todo.dto.TodoConvertRequest;
import com.triples.rougether.userapi.todo.service.TodoConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Todo", description = "투두 관련 API")
@RestController
@RequestMapping("/api/v1/todos")
@RequiredArgsConstructor
public class TodoConversionController {

    private final TodoConversionService todoConversionService;

    @Operation(summary = "투두를 반복 루틴으로 전환",
            description = "소유한 미완료 투두를 반복 루틴으로 바꿉니다. 본문은 루틴 등록(POST /api/v1/routines)과 같은 규칙으로 검증하며, "
                    + "한 트랜잭션에서 루틴을 만들고 투두를 삭제한 뒤 만들어진 루틴을 응답합니다. 검증에 실패하면 투두는 그대로 남습니다. "
                    + "기기 캘린더에서 가져온 투두도 바꿀 수 있으며, 새 루틴에는 외부 참조(externalSource·externalId)를 옮기지 않습니다 — "
                    + "지운 투두가 참조를 계속 갖고 있어 같은 일정을 다시 가져오면 TODO_EXTERNAL_DUPLICATE(409)로 건너뜁니다. "
                    + "완료한 투두는 TODO_COMPLETED_NOT_CONVERTIBLE(409)로 거절하므로 완료를 취소한 뒤 다시 요청합니다. "
                    + "없거나 남의·삭제된 투두는 TODO_NOT_FOUND(404)입니다.")
    @PostMapping("/{id}/convert-to-routine")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineResponse convertToRoutine(@CurrentUser AuthUser authUser,
                                            @Parameter(description = "투두 ID. 내 투두 목록 조회(GET /api/v1/todos) 응답의 id 값") @PathVariable Long id,
                                            @Valid @RequestBody TodoConvertRequest request) {
        return todoConversionService.convertToRoutine(authUser.id(), id, request);
    }
}
