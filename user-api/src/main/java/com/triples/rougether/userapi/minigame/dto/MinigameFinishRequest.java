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
        // 게임별 입력 상한(러너 600 · 탑 쌓기 3,000)은 각 검증기가 강제함. 여기는 가장 큰 상한.
        @Size(max = 3000) List<@NotNull @Min(1) @Max(18000) Integer> jumpTicks,
        @Size(max = 2000) List<@NotNull @Valid MinigameAction> actions,
        // 털실 정리(cat-yarn) 전용: 집은 더미 번호(0..5) 순서. 시계가 없는 게임이라 ticks는 집은 횟수임.
        @Size(max = 2000) List<@NotNull @Min(0) @Max(5) Integer> picks) {

    public MinigameFinishRequest(Integer ticks, List<Integer> jumpTicks) {
        this(ticks, jumpTicks, null, null);
    }

    public MinigameFinishRequest(Integer ticks, List<Integer> jumpTicks, List<MinigameAction> actions) {
        this(ticks, jumpTicks, actions, null);
    }

    public static MinigameFinishRequest ofPicks(Integer ticks, List<Integer> picks) {
        return new MinigameFinishRequest(ticks, null, null, picks);
    }

    @JsonIgnore
    @AssertTrue(message = "점프 입력, 방향 입력, 집기 입력 중 하나만 보내 주세요.")
    public boolean isReplayProvided() {
        int provided = (jumpTicks != null ? 1 : 0) + (actions != null ? 1 : 0) + (picks != null ? 1 : 0);
        return provided == 1;
    }
}
