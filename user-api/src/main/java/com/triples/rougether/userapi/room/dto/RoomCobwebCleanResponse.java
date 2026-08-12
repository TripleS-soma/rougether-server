package com.triples.rougether.userapi.room.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RoomCobwebCleanResponse(
        @Schema(description = "청소한 방의 소유자 회원 ID", example = "8")
        Long roomUserId,
        @Schema(description = "청소 완료 시각")
        Instant cleanedAt,
        @Schema(description = "보상 재화", example = "COIN")
        CurrencyType rewardCurrencyType,
        @Schema(description = "청소 보상량", example = "3")
        int rewardAmount,
        @Schema(description = "보상 반영 후 잔액", example = "13")
        int balance) {
}
