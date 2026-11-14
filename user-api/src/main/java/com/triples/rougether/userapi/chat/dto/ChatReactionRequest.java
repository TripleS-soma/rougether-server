package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatReactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ChatReactionRequest(
        @Schema(description = "공감 유형. 허용값: HEART(하트), LIKE(좋아요), CHECK(확인), LAUGH(웃음), SURPRISE(놀람), SAD(슬픔)",
                example = "HEART")
        @NotNull ChatReactionType type) {}
