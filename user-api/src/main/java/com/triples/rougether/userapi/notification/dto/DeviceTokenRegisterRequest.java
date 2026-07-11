package com.triples.rougether.userapi.notification.dto;

import com.triples.rougether.domain.notification.entity.DevicePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeviceTokenRegisterRequest(
        @Schema(description = "FCM 디바이스 토큰", example = "d1qA...xyz")
        @NotBlank String token,
        @Schema(description = "디바이스 플랫폼. 허용값: IOS, ANDROID", example = "IOS")
        @NotNull DevicePlatform platform
) {
}
