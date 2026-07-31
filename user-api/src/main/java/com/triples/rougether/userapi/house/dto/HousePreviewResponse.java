package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.House;
import io.swagger.v3.oas.annotations.media.Schema;

// GET /api/v1/houses/by-code/{inviteCode} 응답 - 참여 전 미리보기.
// 만료 코드도 200 으로 내려주고 inviteExpired 로 표시한다(화면 만료 안내용).
public record HousePreviewResponse(
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "집 이름", example = "아침 루틴 하우스")
        String name,
        @Schema(description = "커버 이미지 asset key. CDN base URL 과 조합해 이미지 URL 로 사용", example = "house/1f9d1c2e.png")
        String coverImageKey,
        @Schema(description = "현재 구성원 수", example = "3")
        int currentMemberCount,
        @Schema(description = "최대 구성원 수 (null 이면 무제한)", example = "4")
        Integer maxMembers,
        @Schema(description = "초대코드 만료 여부. true 면 이 코드로는 참여(POST /api/v1/houses/join-by-code)할 수 없어 코드를 공유한 구성원의 재발급이 필요하므로 화면에 만료 안내 표시", example = "false")
        boolean inviteExpired,
        @Schema(description = "방장 승인 대기 여부. 집 공용 코드(소유자 공유)는 false(즉시가입), 구성원 개인 코드는 true(참여 시 입주 신청이 생성되고 방장 수락 후 입주 확정)", example = "false")
        boolean requiresApproval) {

    public static HousePreviewResponse of(House house, boolean inviteExpired, boolean requiresApproval) {
        return new HousePreviewResponse(
                house.getId(),
                house.getName(),
                house.getCoverImageKey(),
                house.getCurrentMemberCount(),
                house.getMaxMembers(),
                inviteExpired,
                requiresApproval);
    }
}
