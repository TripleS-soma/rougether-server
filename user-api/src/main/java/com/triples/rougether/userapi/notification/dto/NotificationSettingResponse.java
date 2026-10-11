package com.triples.rougether.userapi.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationSettingResponse(
        @Schema(description = "전체 알림 마스터 스위치. false면 reminder·house·feed·market·chat 값과 무관하게 모든 push가 중단됨", example = "true")
        boolean all,
        @Schema(description = "리마인더 알림(루틴 리마인드·투두 리마인드) 수신 여부", example = "true")
        boolean reminder,
        @Schema(description = "집 알림(입주·퇴거·응원·단체 미션 달성) 수신 여부", example = "false")
        boolean house,
        @Schema(description = "피드 댓글 알림 수신 여부", example = "true")
        boolean feed,
        @Schema(description = "거래소 알림(판매 체결·구매 대기 체결·구매 대기 등록) 수신 여부", example = "true")
        boolean market,
        @Schema(description = "집 채팅 새 메시지 푸시 수신 여부(방별 음소거는 채팅 알림 API)", example = "true")
        boolean chat
) {
}
