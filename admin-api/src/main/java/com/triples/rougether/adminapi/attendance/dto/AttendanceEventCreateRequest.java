package com.triples.rougether.adminapi.attendance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record AttendanceEventCreateRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Z0-9_]+") String code,
        @NotBlank @Size(max = 120) String title,
        @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn,
        @NotNull @Min(2) @Max(365) Integer targetDays,
        @NotNull @Min(0) @Max(1000000) Integer dailyCoinAmount,
        @NotNull @Min(1) @Max(365) Integer bonusDay,
        @NotNull @Min(0) @Max(1000000) Integer bonusCoinAmount,
        @NotNull @Positive Long rewardItemId) {
}
