package com.triples.rougether.userapi.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceTokenDeleteRequest(
        @Schema(description = "삭제할 FCM 디바이스 토큰", example = "d1qA...xyz")
        @NotBlank @Size(max = 255) String token
) {
}
