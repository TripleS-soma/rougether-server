package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import com.triples.rougether.userapi.room.dto.RoomRenderResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/houses/{houseId}/preview 응답 - 비구성원 포함 로그인 회원 누구나(집 정보는 전체공개 정책).
// 구성원 전용 상세와 동일 소싱이되 구성원 전용 의미인 myRole·inviteCode 는 없다.
// memberRooms 는 미리보기 화면의 구성원 타일 렌더용(#177) - 방 렌더 데이터는 전체공개로 확정,
// 활동 정보(streak·lastAccessedAt·그날 현황)는 기존대로 구성원 전용이라 내리지 않는다.
public record HousePreviewDetailResponse(
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "집 이름", example = "아침 루틴 하우스")
        String name,
        @Schema(description = "집 소개", example = "같이 아침 루틴 지켜요")
        String description,
        @Schema(description = "커버 이미지 asset key. CDN base URL 과 조합해 이미지 URL 로 사용", example = "house/1f9d1c2e.png")
        String coverImageKey,
        @Schema(description = "최대 구성원 수 (null 이면 무제한)", example = "4")
        Integer maxMembers,
        @Schema(description = "현재 구성원 수", example = "3")
        int currentMemberCount,
        @Schema(description = "집 레벨 (생성 시 0에서 시작)", example = "2")
        int level,
        List<GoalSummary> goals,
        @Schema(description = "요청자가 이 집의 활성(ACTIVE) 구성원인지. true 면 상세(GET /api/v1/houses/{houseId}) 화면으로 전환", example = "false")
        boolean isMember,
        @Schema(description = "정원 초과 여부. true 면 참여(POST /api/v1/houses/{houseId}/join) 불가 - 가입 버튼 비활성", example = "false")
        boolean isFull,
        @Schema(description = "구성원별 방 렌더 데이터 (가입순, ACTIVE 구성원만). 미리보기 화면의 구성원 타일 렌더용")
        List<MemberRoomSummary> memberRooms) {

    public static HousePreviewDetailResponse of(House house, List<GoalSummary> goals, boolean isMember,
                                                List<MemberRoomSummary> memberRooms) {
        return new HousePreviewDetailResponse(
                house.getId(),
                house.getName(),
                house.getDescription(),
                house.getCoverImageKey(),
                house.getMaxMembers(),
                house.getCurrentMemberCount(),
                house.getLevel(),
                goals,
                isMember,
                house.isFull(),
                memberRooms);
    }

    // 구성원 1명의 방 타일. 활동 정보 없이 렌더 데이터만 - userId·role 등 구성원 관리 필드는
    // 구성원 목록(구성원 전용) 계약에만 둔다.
    public record MemberRoomSummary(
            @Schema(description = "구성원 membership ID (타일 식별용)", example = "12")
            Long membershipId,
            @Schema(description = "닉네임 (온보딩 전이면 null)", example = "진형")
            String nickname,
            @Schema(description = "방 렌더 데이터. 방 미생성(내 방 화면 미방문) 구성원은 null - 기본 방으로 표시")
            RoomRenderResponse room) {

        public static MemberRoomSummary of(HouseMember member, RoomRenderResponse room) {
            return new MemberRoomSummary(member.getId(), member.getUser().getNickname(), room);
        }
    }
}
