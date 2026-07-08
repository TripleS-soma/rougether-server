package com.triples.rougether.userapi.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberUpdateRequest(
        @Schema(description = "변경할 닉네임(최대 30자, 공백 불가)", example = "루티니")
        @NotBlank @Size(max = 30) String nickname,
        @Schema(description = "한줄 소개(최대 100자, 선택사항)", example = "루틴을 사랑하는 사람")
        @Size(max = 100) String bio
) {
}
