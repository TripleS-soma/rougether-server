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
    public static final String TOWER = "cat-tower";
    public static final int RULES_VERSION = 1;
    // v3: 러너 속도 곡선 변경 (mobile #1322). 계단·합치기는 v2와 같은 물리로 3을 받음.
    // 탑 쌓기(mobile #1529)는 v3에서 처음 생겨 3 미만 목록에는 없고 3 미만으로 시작할 수 없음.
    public static final int CURRENT_RULES_VERSION = 3;
    private static final List<Entry> ENTRIES = List.of(
            new Entry(RUNNER, "루틴 러너", "탭해서 장애물을 넘고, 최고 기록에 도전해요.", RULES_VERSION),
            new Entry(STAIRS, "고양이 계단", "왼쪽, 오른쪽! 고양이와 더 높이 올라가요.", RULES_VERSION),
            new Entry(MERGE, "고양이 합치기", "같은 숫자의 고양이를 합쳐 더 큰 숫자를 만들어요.", RULES_VERSION),
            new Entry(TOWER, "고양이 탑 쌓기", "흔들리는 상자를 딱 맞춰 쌓아 올려요.", 3));

    public MinigameListResponse list() {
        return list(RULES_VERSION);
    }

    public MinigameListResponse list(int rulesVersion) {
        requireRulesVersion(rulesVersion);
        return new MinigameListResponse(ENTRIES.stream()
                .filter(entry -> entry.supports(rulesVersion))
                .map(entry -> new MinigameListResponse.Item(entry.gameCode(), entry.name(), entry.description(), rulesVersion))
                .toList());
    }

    public void requireRulesVersion(int rulesVersion) {
        if (rulesVersion < RULES_VERSION || rulesVersion > CURRENT_RULES_VERSION) {
            throw new BusinessException(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED);
        }
    }

    // 게임이 생기기 전 버전으로는 세션을 발급하지 않음. 그 버전의 앱은 해당 게임의 엔진이 없음.
    public void requireRulesVersion(String gameCode, int rulesVersion) {
        requireRulesVersion(rulesVersion);
        if (!entry(gameCode).supports(rulesVersion)) {
            throw new BusinessException(MinigameErrorCode.RULES_VERSION_NOT_SUPPORTED);
        }
    }

    public void requireGame(String gameCode) {
        entry(gameCode);
    }

    private static Entry entry(String gameCode) {
        return ENTRIES.stream()
                .filter(entry -> entry.gameCode().equals(gameCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(MinigameErrorCode.GAME_NOT_FOUND));
    }

    private record Entry(String gameCode, String name, String description, int minRulesVersion) {
        private boolean supports(int rulesVersion) {
            return rulesVersion >= minRulesVersion;
        }
    }
}
