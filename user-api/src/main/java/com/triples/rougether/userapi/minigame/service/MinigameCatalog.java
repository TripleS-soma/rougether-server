package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.minigame.dto.MinigameListResponse;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MinigameCatalog {
    public static final String RUNNER = "room-runner";
    public static final String STAIRS = "cat-stairs";
    public static final String MERGE = "cat-merge";
    public static final int RULES_VERSION = 1;
    private static final MinigameListResponse.Item RUNNER_ITEM = new MinigameListResponse.Item(
            RUNNER, "루틴 러너", "탭해서 장애물을 넘고, 최고 기록에 도전해요.", RULES_VERSION);
    private static final List<MinigameListResponse.Item> ITEMS = List.of(RUNNER_ITEM,
            new MinigameListResponse.Item(STAIRS, "고양이 계단", "왼쪽, 오른쪽! 고양이와 더 높이 올라가요.", RULES_VERSION),
            new MinigameListResponse.Item(MERGE, "고양이 합치기", "같은 숫자의 고양이를 합쳐 더 큰 숫자를 만들어요.", RULES_VERSION));

    public MinigameListResponse list() {
        return new MinigameListResponse(ITEMS);
    }

    public void requireGame(String gameCode) {
        if (ITEMS.stream().noneMatch(item -> item.gameCode().equals(gameCode))) {
            throw new BusinessException(MinigameErrorCode.GAME_NOT_FOUND);
        }
    }
}
