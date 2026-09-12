package com.triples.rougether.userapi.minigame.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MinigameFinishRequest(
        @NotNull @Min(1) @Max(18000) Integer ticks,
        @Size(max = 600) List<@NotNull @Min(1) @Max(18000) Integer> jumpTicks,
        @Size(max = 2000) List<@NotNull @Valid MinigameAction> actions) {

    public MinigameFinishRequest(Integer ticks, List<Integer> jumpTicks) {
        this(ticks, jumpTicks, null);
    }

    @JsonIgnore
    @AssertTrue(message = "점프 입력 또는 방향 입력 중 하나를 보내 주세요.")
    public boolean isReplayProvided() {
        return (jumpTicks != null) != (actions != null);
    }
}
