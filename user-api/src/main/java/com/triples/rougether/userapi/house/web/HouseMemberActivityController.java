package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseCheerRequest;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberDayResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberRoutineCompletionListResponse;
import com.triples.rougether.userapi.house.service.HouseCheerService;
import com.triples.rougether.userapi.house.service.HouseMemberActivityService;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 같은 집 멤버 활동 열람·응원 - 방/그날 현황(루틴+투두)/완료 내역/응원 보내기.
// 요청자·대상 모두 그 집의 ACTIVE 구성원이어야 한다(열람은 본인도 가능, 응원은 본인 불가).
@Tag(name = "House Member Activity", description = "같은 집 멤버의 방·그날 현황(루틴+투두)·완료 내역 열람과 응원 API")
@RestController
@RequestMapping("/api/v1/houses/{houseId}/members/{membershipId}")
public class HouseMemberActivityController {

    private final HouseMemberActivityService houseMemberActivityService;
    private final HouseCheerService houseCheerService;

    public HouseMemberActivityController(HouseMemberActivityService houseMemberActivityService,
                                         HouseCheerService houseCheerService) {
        this.houseMemberActivityService = houseMemberActivityService;
        this.houseCheerService = houseCheerService;
    }

    @Operation(summary = "집 멤버 방 조회",
            description = "같은 집 멤버의 방을 조회합니다. 요청자와 조회 대상 모두 해당 집(houseId)의 활성(ACTIVE) 구성원이어야 하며, "
                    + "본인(내 membershipId)도 조회할 수 있습니다. "
                    + "응답 형태는 내 방 조회(GET /api/v1/rooms/me)와 동일합니다 — 방 성장 레벨, 착용 캐릭터, "
                    + "벽지/바닥/배경(surface)·가구(positioned) 슬롯별 배치와 아이템 assetKey, 스트릭이 내려갑니다. "
                    + "대상 회원이 아직 방을 만들지 않았으면(내 방 화면 미방문) 404(ROOM_NOT_FOUND)를 반환합니다.")
    @GetMapping("/room")
    public RoomResponse getMemberRoom(
            @CurrentUser AuthUser user,
            @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값") @PathVariable Long houseId,
            @Parameter(description = "조회 대상 구성원의 membership ID. GET /api/v1/houses/{houseId}/members (구성원 목록) 응답의 membershipId 값")
            @PathVariable Long membershipId) {
        return houseMemberActivityService.getMemberRoom(user.id(), houseId, membershipId);
    }

    @Operation(summary = "집 멤버 그날 현황 조회 (루틴 + 투두, 완료 여부 포함)",
            description = "같은 집 멤버의 그날(date, 미지정 시 오늘 KST) 현황을 반환합니다 — 그날 반복 대상인 진행 중(ACTIVE) "
                    + "루틴(완료 여부 completed 포함)과 그날 마감(dueDate=date) 투두(status/completedAt 포함). "
                    + "반복 대상·완료 판정은 오늘 현황(GET /api/v1/today)·캘린더와 동일 규칙이고, "
                    + "요청자와 조회 대상 모두 해당 집(houseId)의 활성(ACTIVE) 구성원이어야 합니다. "
                    + "루틴은 수행 예정 시각 오름차순, 투두는 id 오름차순으로 정렬됩니다. "
                    + "카테고리 공개 범위(visibility)가 HOUSE(집) 또는 PUBLIC(공개)인 루틴·투두만 내려가고, "
                    + "PRIVATE(비공개)·FRIENDS(친한친구) 카테고리와 미분류(카테고리 없음)는 제외됩니다. "
                    + "본인을 조회해도 같은 공개 범위 필터가 적용되므로, 내 화면에는 GET /api/v1/today 를 사용하세요.")
    @GetMapping("/day")
    public HouseMemberDayResponse getMemberDay(
            @CurrentUser AuthUser user,
            @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값") @PathVariable Long houseId,
            @Parameter(description = "조회 대상 구성원의 membership ID. GET /api/v1/houses/{houseId}/members (구성원 목록) 응답의 membershipId 값")
            @PathVariable Long membershipId,
            @Parameter(description = "기준 날짜(YYYY-MM-DD). 그날 반복 대상 루틴·그날 마감 투두만 반환. 미지정 시 오늘(KST)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return houseMemberActivityService.getMemberDay(user.id(), houseId, membershipId, date);
    }

    @Operation(summary = "집 멤버 루틴 완료 내역 조회",
            description = "같은 집 멤버의 루틴 완료 내역을 기간으로 조회합니다. 요청자와 조회 대상 모두 해당 집(houseId)의 "
                    + "활성(ACTIVE) 구성원이어야 합니다. "
                    + "to 미지정 시 오늘(KST), from 미지정 시 to 기준 최근 14일이 적용되며 실제 적용된 기간이 응답 from/to 로 내려갑니다. "
                    + "기간은 최대 92일이고 from 이 to 보다 뒤면 400(HOUSE_ACTIVITY_PERIOD_INVALID)입니다. "
                    + "루틴 목록과 동일하게 카테고리 공개 범위 HOUSE/PUBLIC 인 루틴의 완료 내역만 내려가며, "
                    + "완료 날짜 내림차순(같은 날짜는 완료 시각 내림차순)으로 정렬됩니다. "
                    + "스케줄 수정으로 루틴 버전이 갈린 경우에도 과거 완료는 그대로 포함되며, 같은 루틴 묶음 판별에는 originRoutineId 를 사용하세요.")
    @GetMapping("/routine-completions")
    public HouseMemberRoutineCompletionListResponse getMemberRoutineCompletions(
            @CurrentUser AuthUser user,
            @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값") @PathVariable Long houseId,
            @Parameter(description = "조회 대상 구성원의 membership ID. GET /api/v1/houses/{houseId}/members (구성원 목록) 응답의 membershipId 값")
            @PathVariable Long membershipId,
            @Parameter(description = "조회 시작일(YYYY-MM-DD). 미지정 시 to 기준 최근 14일")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일(YYYY-MM-DD). 미지정 시 오늘(KST)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return houseMemberActivityService.getMemberRoutineCompletions(user.id(), houseId, membershipId, from, to);
    }

    // 원탭 응원. 저장 커밋 후 대상에게 push 알림(FRIEND_CHEER)이 발송된다.
    @Operation(summary = "집 멤버에게 응원 보내기",
            description = "같은 집 멤버에게 원탭 응원을 보냅니다. 요청자와 대상 모두 해당 집(houseId)의 활성(ACTIVE) 구성원이어야 하고, "
                    + "자기 자신에게는 보낼 수 없습니다(400 HOUSE_CHEER_SELF). "
                    + "type 은 great(잘하고 있어!)/support(응원해요!)/best(오늘도 최고!) 3종이며, "
                    + "같은 대상에게 같은 타입은 하루(KST) 1회만 보낼 수 있습니다(초과 시 409 HOUSE_CHEER_DUPLICATED). "
                    + "저장이 확정되면 대상에게 push 알림(응원이 도착했어요)이 발송됩니다 — 알림 실패는 응원 성공에 영향을 주지 않습니다.")
    @PostMapping("/cheer")
    @ResponseStatus(HttpStatus.CREATED)
    public HouseCheerResponse cheer(
            @CurrentUser AuthUser user,
            @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값") @PathVariable Long houseId,
            @Parameter(description = "응원 대상 구성원의 membership ID. GET /api/v1/houses/{houseId}/members (구성원 목록) 응답의 membershipId 값")
            @PathVariable Long membershipId,
            @Valid @RequestBody HouseCheerRequest request) {
        return houseCheerService.cheer(user.id(), houseId, membershipId, request.type());
    }
}
