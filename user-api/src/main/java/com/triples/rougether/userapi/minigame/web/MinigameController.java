package com.triples.rougether.userapi.minigame.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameLeaderboardResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameListResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameRunStartResponse;
import com.triples.rougether.userapi.minigame.service.MinigameCatalog;
import com.triples.rougether.userapi.minigame.service.MinigameCommandService;
import com.triples.rougether.userapi.minigame.service.MinigameQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Minigame", description = "미니게임 플레이와 전체 사용자 랭킹")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/minigames")
public class MinigameController {
    private final MinigameCatalog catalog;
    private final MinigameCommandService commandService;
    private final MinigameQueryService queryService;

    @Operation(summary = "이용 가능한 미니게임 목록", description = "선택한 규칙 버전의 목록을 반환합니다. rulesVersion 생략 시 기존 버전 1을 사용합니다.")
    @GetMapping
    public MinigameListResponse list(@CurrentUser AuthUser user,
                                     @RequestParam(defaultValue = "1") int rulesVersion) {
        return catalog.list(rulesVersion);
    }

    @Operation(summary = "게임 시작", description = "선택한 규칙 버전으로 플레이 세션과 seed를 발급합니다. rulesVersion 생략 시 버전 1을 사용합니다.")
    @PostMapping("/{gameCode}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public MinigameRunStartResponse start(@CurrentUser AuthUser user, @PathVariable String gameCode,
                                          @RequestParam(defaultValue = "1") int rulesVersion) {
        return commandService.start(user.id(), gameCode, rulesVersion);
    }

    @Operation(summary = "플레이 기록 제출", description = "게임별 점프 또는 방향 입력을 서버에서 재생해 점수를 계산합니다. 같은 기록 재제출은 멱등합니다.")
    @PostMapping("/{gameCode}/runs/{runId}/finish")
    public MinigameFinishResponse finish(@CurrentUser AuthUser user, @PathVariable String gameCode,
                                         @PathVariable String runId, @Valid @RequestBody MinigameFinishRequest request) {
        return commandService.finish(user.id(), gameCode, runId, request);
    }

    @Operation(summary = "게임별 전체 사용자 랭킹", description = "누적 최고점 상위 50명과 내 순위를 반환합니다. 동점은 공동 순위입니다.")
    @GetMapping("/{gameCode}/leaderboard")
    public MinigameLeaderboardResponse leaderboard(@CurrentUser AuthUser user, @PathVariable String gameCode) {
        return queryService.leaderboard(user.id(), gameCode);
    }
}
