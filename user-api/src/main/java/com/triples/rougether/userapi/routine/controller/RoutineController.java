package com.triples.rougether.userapi.routine.controller;

import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineListResponse;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.dto.StreakSummaryResponse;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import com.triples.rougether.userapi.routine.service.RoutineService;
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

@Tag(name = "Routine", description = "루틴 관련 API")
@RestController
@RequestMapping("/api/v1/routines")
@RequiredArgsConstructor
public class RoutineController {

    private final RoutineService routineService;
    private final RoutineLogService routineLogService;

    @Operation(summary = "내 루틴 목록 조회",
            description = "로그인한 회원이 소유한 루틴을 반환합니다. categoryId·status로 필터링할 수 있습니다.")
    @GetMapping
    public RoutineListResponse list(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "카테고리 ID 필터") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "상태 필터") @RequestParam(required = false) RoutineStatus status) {
        return routineService.list(authUser.id(), categoryId, status);
    }

    @Operation(summary = "루틴 단건 조회", description = "소유한 루틴 한 건을 반환합니다.")
    @GetMapping("/{id}")
    public RoutineResponse get(@CurrentUser AuthUser authUser,
                               @Parameter(description = "루틴 ID") @PathVariable Long id) {
        return routineService.get(authUser.id(), id);
    }

    @Operation(summary = "루틴 등록", description = "로그인한 회원의 새 루틴을 등록합니다. 상태는 ACTIVE로 시작합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineResponse create(@CurrentUser AuthUser authUser,
                                  @Valid @RequestBody RoutineCreateRequest request) {
        return routineService.create(authUser.id(), request);
    }

    @Operation(summary = "루틴 수정", description = "소유한 루틴의 속성을 수정합니다. 지정하지 않은 필드는 변경하지 않습니다.")
    @PutMapping("/{id}")
    public RoutineResponse update(@CurrentUser AuthUser authUser,
                                  @Parameter(description = "루틴 ID") @PathVariable Long id,
                                  @Valid @RequestBody RoutineUpdateRequest request) {
        return routineService.update(authUser.id(), id, request);
    }

    @Operation(summary = "루틴 삭제", description = "소유한 루틴을 삭제합니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser authUser,
                       @Parameter(description = "루틴 ID") @PathVariable Long id) {
        routineService.delete(authUser.id(), id);
    }

    @Operation(summary = "루틴 완료 체크",
            description = "당일 루틴을 완료 처리합니다. 코인 10을 지급하고 스트릭을 갱신합니다.")
    @PostMapping("/{id}/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineLogResponse complete(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "루틴 ID") @PathVariable Long id,
            @RequestBody(required = false) RoutineLogCreateRequest request) {
        RoutineLogCreateRequest body = request != null ? request : new RoutineLogCreateRequest(null);
        return routineLogService.complete(authUser.id(), id, body);
    }

    @Operation(summary = "루틴 완료 취소",
            description = "당일 완료 기록을 취소합니다. 지급한 코인을 회수하고 스트릭을 롤백합니다.")
    @DeleteMapping("/{id}/logs/{logId}")
    public StreakSummaryResponse cancelLog(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "루틴 ID") @PathVariable Long id,
            @Parameter(description = "완료 기록 ID") @PathVariable Long logId) {
        return routineLogService.cancel(authUser.id(), id, logId);
    }
}
