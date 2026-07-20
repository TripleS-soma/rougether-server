package com.triples.rougether.userapi.notification.dto;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record NotificationListResponse(
        List<NotificationItem> items,
        @Schema(description = "다음 페이지 커서 (다음 요청의 cursor 로 전달, 더 없으면 null)", example = "12")
        Long nextCursor,
        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext) {

    public record NotificationItem(
            @Schema(description = "알림 ID. 알림 읽음 처리(PATCH /api/v1/notifications/{notificationId}/read)의 notificationId 로 사용", example = "12")
            Long notificationId,
            @Schema(description = "알림 종류: HOUSE_KICK(집에서 내보내짐), ROUTINE_REMINDER(루틴 리마인드), "
                    + "FRIEND_CHEER(집 멤버 응원 도착)", example = "ROUTINE_REMINDER")
            NotificationType type,
            @Schema(description = "알림 제목", example = "루틴 리마인드")
            String title,
            @Schema(description = "알림 내용", example = "물 마시기 할 시간이에요")
            String body,
            @Schema(description = "읽음 여부", example = "false")
            boolean isRead,
            @Schema(description = "알림 생성 시각. 목록은 최신순(notificationId 내림차순) 정렬")
            Instant createdAt) {

        public static NotificationItem of(Notification notification) {
            return new NotificationItem(
                    notification.getId(),
                    notification.getType(),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.isRead(),
                    notification.getCreatedAt());
        }
    }
}
