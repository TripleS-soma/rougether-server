package com.triples.rougether.userapi.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;

public record MemberPreferencesRequest(
        @Schema(description = "앱 언어. ko 또는 en. 생략하면 유지")
        @Pattern(regexp = "ko|en") String language,
        @Schema(description = "개인 리마인드 시간대. IANA ID. 생략하면 유지", example = "America/New_York")
        @Size(max = 64) String timeZone) {
    @AssertTrue(message = "language or timeZone is required")
    public boolean isNotEmpty() { return language != null || timeZone != null; }

    @AssertTrue(message = "timeZone must be an IANA time zone ID")
    public boolean isTimeZoneValid() {
        return timeZone == null || ZoneId.getAvailableZoneIds().contains(timeZone);
    }
}
