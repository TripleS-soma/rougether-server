package com.triples.rougether.userapi.member.dto;

import com.triples.rougether.domain.member.entity.WithdrawalReason;
import io.swagger.v3.oas.annotations.media.Schema;

public record MemberWithdrawalRequest(
        @Schema(description = "선택형 탈퇴 사유. 생략 가능하며 미선택이어도 탈퇴할 수 있음")
        WithdrawalReason reason) {
}
