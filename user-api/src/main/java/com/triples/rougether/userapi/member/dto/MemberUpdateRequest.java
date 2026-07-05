package com.triples.rougether.userapi.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberUpdateRequest(
        @Schema(description = "변경할 닉네임(최대 30자, 공백 불가)", example = "루티니")
        @NotBlank @Size(max = 30) String nickname
) {
}
