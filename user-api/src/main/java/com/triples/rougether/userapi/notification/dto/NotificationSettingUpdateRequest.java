package com.triples.rougether.userapi.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;

public record NotificationSettingUpdateRequest(
        @Schema(description = "전체 알림 마스터 스위치. false면 reminder·house 값과 무관하게 모든 push가 중단됨. "
                + "바꾸지 않으려면 생략", example = "true")
        Boolean all,
        @Schema(description = "리마인더 알림(루틴 리마인드·투두 리마인드) 수신 여부. 바꾸지 않으려면 생략", example = "true")
        Boolean reminder,
        @Schema(description = "집 알림(입주·퇴거·응원·단체 미션 달성) 수신 여부. 바꾸지 않으려면 생략", example = "false")
        Boolean house
) {

    // 부분 전송이라 세 필드 모두 optional — 전부 생략되면 바꿀 게 없으므로 400.
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "변경할 항목을 최소 하나 지정해야 합니다.")
    public boolean isAnyPresent() {
        return all != null || reminder != null || house != null;
    }
}
