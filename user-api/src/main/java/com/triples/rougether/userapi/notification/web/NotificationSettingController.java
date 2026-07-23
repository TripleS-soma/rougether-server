package com.triples.rougether.userapi.notification.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.notification.dto.NotificationSettingResponse;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification", description = "알림 관련 API")
@RestController
@RequestMapping("/api/v1/users/me/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @Operation(summary = "알림 설정 조회",
            description = "로그인한 회원의 push 알림 설정을 반환합니다. 한 번도 끈 적이 없는 항목은 켜짐(true)으로 반환합니다. "
                    + "설정을 꺼도 알림함(알림 목록 조회)에는 그대로 쌓이며 push 발송만 중단됩니다.")
    @GetMapping
    public NotificationSettingResponse getSettings(@CurrentUser AuthUser user) {
        return notificationSettingService.getSettings(user.id());
    }

    @Operation(summary = "알림 설정 변경",
            description = "push 알림 설정을 부분 변경하고 변경이 반영된 전체 설정을 반환합니다. 바꿀 항목만 담아 보내고, "
                    + "생략한 항목은 기존 값이 유지됩니다. 최소 한 항목은 담아야 합니다. "
                    + "all을 false로 두면 reminder·house 값과 무관하게 모든 push가 중단되며, 그때도 각 그룹 값은 보존되어 "
                    + "all을 다시 true로 되돌리면 이전 그룹 설정이 그대로 적용됩니다.")
    @PatchMapping
    public NotificationSettingResponse updateSettings(
            @CurrentUser AuthUser user,
            @Valid @RequestBody NotificationSettingUpdateRequest request) {
        return notificationSettingService.updateSettings(user.id(), request);
    }
}
