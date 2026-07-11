package com.triples.rougether.userapi.notification.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.notification.dto.DeviceTokenDeleteRequest;
import com.triples.rougether.userapi.notification.dto.DeviceTokenRegisterRequest;
import com.triples.rougether.userapi.notification.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification", description = "알림 관련 API")
@RestController
@RequestMapping("/api/v1/users/me/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @Operation(summary = "디바이스 토큰 등록",
            description = "FCM 디바이스 토큰을 등록합니다. 같은 token 재등록은 멱등하게 처리되고, "
                    + "다른 사용자가 등록했던 token이면 소유자가 현재 로그인한 사용자로 이전됩니다(기기 재로그인 케이스).")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@CurrentUser AuthUser user,
                         @Valid @RequestBody DeviceTokenRegisterRequest request) {
        deviceTokenService.register(user.id(), request.token(), request.platform());
    }

    @Operation(summary = "디바이스 토큰 삭제",
            description = "로그아웃 시 프론트가 호출합니다. 본인 소유 token만 삭제할 수 있습니다.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser user,
                       @Valid @RequestBody DeviceTokenDeleteRequest request) {
        deviceTokenService.delete(user.id(), request.token());
    }
}
