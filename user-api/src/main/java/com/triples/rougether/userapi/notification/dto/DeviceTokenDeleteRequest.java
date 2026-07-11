package com.triples.rougether.userapi.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record DeviceTokenDeleteRequest(
        @Schema(description = "삭제할 FCM 디바이스 토큰", example = "d1qA...xyz")
        @NotBlank String token
) {
}
