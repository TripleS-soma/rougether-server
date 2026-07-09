package com.triples.rougether.userapi.routine.web;

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

@Tag(name = "Routine", description = "루틴 관련 API")
@RestController
@RequestMapping("/api/v1/routines")
@RequiredArgsConstructor
public class RoutineController {

    private final RoutineService routineService;
    private final RoutineLogService routineLogService;

    @Operation(summary = "내 루틴 목록 조회",
            description = "로그인한 회원이 소유한 루틴을 반환합니다. categoryId·status로 필터링할 수 있습니다. "
                    + "categoryId는 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id를 사용합니다. "
                    + "수행 예정 시각(scheduledTime) 오름차순, 같으면 id 오름차순으로 정렬합니다. "
                    + "필터를 지정하지 않으면 모든 카테고리·모든 상태의 루틴을 반환하며, 삭제한 루틴은 포함하지 않습니다.")
    @GetMapping
    public RoutineListResponse list(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "카테고리 ID 필터. 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값. 미지정 시 전체 카테고리")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "상태 필터. 허용값: ACTIVE(진행 중). 미지정 시 전체 상태")
            @RequestParam(required = false) RoutineStatus status) {
        return routineService.list(authUser.id(), categoryId, status);
    }

    @Operation(summary = "루틴 단건 조회", description = "소유한 루틴 한 건을 반환합니다.")
    @GetMapping("/{id}")
    public RoutineResponse get(@CurrentUser AuthUser authUser,
                               @Parameter(description = "루틴 ID. 내 루틴 목록 조회(GET /api/v1/routines) 응답의 id 값") @PathVariable Long id) {
        return routineService.get(authUser.id(), id);
    }

    @Operation(summary = "루틴 등록",
            description = "로그인한 회원의 새 루틴을 등록합니다. 상태는 ACTIVE로 시작합니다. "
                    + "categoryId를 지정하지 않으면 미분류로 등록되며, 소유한 카테고리만 지정할 수 있습니다. "
                    + "repeatType이 WEEKLY이면 repeatDays.daysOfWeek로 반복 요일을 지정합니다(DAILY면 repeatDays 생략).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineResponse create(@CurrentUser AuthUser authUser,
                                  @Valid @RequestBody RoutineCreateRequest request) {
        return routineService.create(authUser.id(), request);
    }

    @Operation(summary = "루틴 수정",
            description = "소유한 루틴의 속성을 수정합니다. 지정하지 않은(null) 필드는 변경하지 않으며, title은 공백이면 기존 값을 유지합니다. "
                    + "categoryId를 지정하면 소유한 해당 카테고리로 이동합니다(null이면 기존 카테고리 유지). "
                    + "repeatType을 WEEKLY로 변경할 때는 repeatDays를 함께 전달해야 요일 기준으로 반복됩니다.")
    @PutMapping("/{id}")
    public RoutineResponse update(@CurrentUser AuthUser authUser,
                                  @Parameter(description = "루틴 ID. 내 루틴 목록 조회(GET /api/v1/routines) 응답의 id 값") @PathVariable Long id,
                                  @Valid @RequestBody RoutineUpdateRequest request) {
        return routineService.update(authUser.id(), id, request);
    }

    @Operation(summary = "루틴 삭제",
            description = "소유한 루틴을 삭제합니다. 삭제한 루틴은 루틴 목록·단건 조회·오늘 현황에서 더 이상 조회되지 않습니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser authUser,
                       @Parameter(description = "루틴 ID. 내 루틴 목록 조회(GET /api/v1/routines) 응답의 id 값") @PathVariable Long id) {
        routineService.delete(authUser.id(), id);
    }

    @Operation(summary = "루틴 완료 체크",
            description = "루틴을 완료 처리합니다. 오늘(KST 기준) 이전 날짜만 완료할 수 있으며 과거 날짜도 허용됩니다. "
                    + "코인 10은 오늘 완료에만 지급하며(일일 상한 4건 적용), 과거 날짜 완료는 0코인입니다. "
                    + "같은 루틴은 같은 날짜에 한 번만 완료할 수 있습니다. "
                    + "스트릭은 오늘의 첫 완료(루틴 종류 무관)에만 갱신됩니다 — 어제가 성공일이면 currentCount가 1 증가하고, 아니면 1부터 다시 시작합니다. "
                    + "과거 날짜 완료는 스트릭에 반영하지 않고 기존 스트릭 요약을 그대로 반환합니다. "
                    + "요청 본문은 생략할 수 있으며(routineDate 미지정 시 오늘로 처리), 응답에 스트릭 요약이 포함됩니다.")
    @PostMapping("/{id}/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineLogResponse complete(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "루틴 ID. 내 루틴 목록 조회(GET /api/v1/routines) 응답의 id 값") @PathVariable Long id,
            @RequestBody(required = false) RoutineLogCreateRequest request) {
        RoutineLogCreateRequest body = request != null ? request : new RoutineLogCreateRequest(null);
        return routineLogService.complete(authUser.id(), id, body);
    }

    @Operation(summary = "루틴 완료 취소",
            description = "완료 기록을 취소합니다. 오늘(KST 기준) 이전 날짜의 완료만 취소할 수 있으며 과거 완료도 취소할 수 있습니다. "
                    + "실제 지급했던 코인만 회수하며(과거 완료는 0코인이라 환불 없음, 잔액이 부족해도 그대로 차감) 완료 기록을 삭제합니다. "
                    + "date에는 화면에서 보고 있는 날짜를 보냅니다. "
                    + "오늘 완료 취소 후 오늘 완료한 루틴이 하나도 남지 않으면 스트릭을 롤백합니다(currentCount 1 감소, longestCount는 유지). "
                    + "과거 완료 취소는 스트릭을 변경하지 않으며, 응답으로 반영된 최종 스트릭 요약을 반환합니다.")
    @DeleteMapping("/{id}/logs")
    public StreakSummaryResponse cancelLog(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "루틴 ID. 내 루틴 목록 조회(GET /api/v1/routines) 응답의 id 값") @PathVariable Long id,
            @Parameter(description = "취소할 완료의 날짜(YYYY-MM-DD). 화면에서 보고 있는 날짜를 그대로 전달. 오늘(KST 기준) 이전 날짜만 사용")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return routineLogService.cancel(authUser.id(), id, date);
    }
}
