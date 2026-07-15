package com.triples.rougether.userapi.notification.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.notification.dto.NotificationListResponse;
import com.triples.rougether.userapi.notification.service.NotificationCommandService;
import com.triples.rougether.userapi.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification", description = "알림 관련 API")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationCommandService notificationCommandService;

    @Operation(summary = "알림 목록 조회",
            description = "내 알림을 최신순으로 조회합니다. 무한스크롤용 커서 방식입니다 — 첫 요청은 cursor 없이 보내고, "
                    + "다음 페이지는 응답의 nextCursor 를 cursor 로 전달합니다(hasNext=false 면 끝).")
    @GetMapping
    public NotificationListResponse getNotifications(
            @CurrentUser AuthUser user,
            @Parameter(description = "커서 (이전 응답의 nextCursor. 첫 요청은 생략)")
            @RequestParam(required = false) Long cursor,
            @Parameter(description = "페이지 크기 (기본 20, 최대 50)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return notificationQueryService.getNotifications(user.id(), cursor, size);
    }

    @Operation(summary = "알림 읽음 처리",
            description = "알림을 읽음으로 표시합니다. 본인 알림만 처리할 수 있고, 이미 읽은 알림에 다시 호출해도 결과는 같습니다. "
                    + "읽음 해제(되돌리기)는 지원하지 않습니다.")
    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @CurrentUser AuthUser user,
            @Parameter(description = "알림 ID. GET /api/v1/notifications (알림 목록 조회) 응답의 notificationId 값")
            @PathVariable Long notificationId) {
        notificationCommandService.markRead(user.id(), notificationId);
    }

    @Operation(summary = "알림 전체 읽음 처리",
            description = "내 안 읽은 알림을 모두 읽음으로 표시합니다.")
    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@CurrentUser AuthUser user) {
        notificationCommandService.markAllRead(user.id());
    }
}
