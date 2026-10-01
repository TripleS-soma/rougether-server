package com.triples.rougether.userapi.moderation.dto;

import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContentReportRequest(
        @Schema(description = "신고 사유. 허용값: SPAM(스팸·광고), ABUSE(욕설·괴롭힘), SEXUAL(선정적), VIOLENCE(폭력·위협), "
                + "PERSONAL_INFO(개인정보 노출), COPYRIGHT(저작권 침해), OTHER(기타)", example = "ABUSE")
        @NotNull ContentReportReason reason,
        @Schema(description = "추가 설명(선택, 최대 500자). 앞뒤 공백을 제거하고 빈 값은 저장하지 않음", example = "욕설이 포함돼 있어요",
                nullable = true)
        @Size(max = 500) String detail) {
}
