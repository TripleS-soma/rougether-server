package com.triples.rougether.userapi.minigame.error;

import com.triples.rougether.common.error.ErrorCode;

public enum MinigameErrorCode implements ErrorCode {
    GAME_NOT_FOUND("MINIGAME_NOT_FOUND", "게임을 찾을 수 없습니다.", 404),
    RULES_VERSION_NOT_SUPPORTED("MINIGAME_RULES_VERSION_NOT_SUPPORTED", "지원하지 않는 게임 규칙 버전입니다.", 400),
    RUN_NOT_FOUND("MINIGAME_RUN_NOT_FOUND", "게임 기록을 찾을 수 없습니다.", 404),
    RUN_EXPIRED("MINIGAME_RUN_EXPIRED", "게임 기록 제출 시간이 지났습니다. 새 게임을 시작해 주세요.", 410),
    INVALID_REPLAY("MINIGAME_INVALID_REPLAY", "게임 플레이 기록이 올바르지 않습니다.", 400),
    RUN_NOT_FINISHED("MINIGAME_RUN_NOT_FINISHED", "아직 끝나지 않은 게임입니다.", 400),
    RUN_TOO_EARLY("MINIGAME_RUN_TOO_EARLY", "게임 진행 시간보다 이른 기록입니다.", 400),
    RUN_ALREADY_FINISHED("MINIGAME_RUN_ALREADY_FINISHED", "이미 다른 플레이 기록으로 제출한 게임입니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    MinigameErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override public String code() { return code; }
    @Override public String message() { return message; }
    @Override public int status() { return status; }
}
