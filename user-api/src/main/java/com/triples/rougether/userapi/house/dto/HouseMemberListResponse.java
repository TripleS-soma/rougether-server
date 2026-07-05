package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/houses/{houseId}/members 응답. ACTIVE 구성원만, 가입순(생성자=OWNER 가 첫 번째).
public record HouseMemberListResponse(List<MemberSummary> items) {

    public record MemberSummary(
            @Schema(description = "구성원 membership ID. 구성원 강퇴(DELETE /api/v1/houses/{houseId}/members/{membershipId}) 경로와 소유권 양도 요청의 targetMembershipId 로 사용", example = "12")
            Long membershipId,
            @Schema(description = "회원 ID", example = "7")
            Long userId,
            @Schema(description = "닉네임 (온보딩 전이면 null)", example = "진형")
            String nickname,
            @Schema(description = "역할. OWNER(소유자 — 설정 수정·초대코드 재발급·강퇴·소유권 양도 가능, 집마다 1명), MEMBER(일반 구성원)", example = "OWNER")
            HouseMemberRole role,
            @Schema(description = "상태 (목록엔 ACTIVE 만 노출). ACTIVE(활동 중), LEFT(탈퇴 — 재참여 가능), KICKED(강퇴 — 재참여 불가)", example = "ACTIVE")
            HouseMemberStatus status,
            @Schema(description = "가입 시각. 목록은 이 값 오름차순(가입순) 정렬")
            Instant joinedAt) {

        public static MemberSummary of(HouseMember member) {
            return new MemberSummary(
                    member.getId(),
                    member.getUser().getId(),
                    member.getUser().getNickname(),
                    member.getRole(),
                    member.getStatus(),
                    member.getJoinedAt());
        }
    }
}
