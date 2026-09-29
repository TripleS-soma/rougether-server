package com.triples.rougether.userapi.moderation.error;

import com.triples.rougether.common.error.ErrorCode;

// 신고·차단(#399) 에러 코드. 대상 없음은 각 도메인의 기존 NOT_FOUND 코드를 그대로 씀.
public enum ModerationErrorCode implements ErrorCode {

    REPORT_SELF_TARGET("REPORT_SELF_TARGET", "내가 올린 콘텐츠는 신고할 수 없습니다.", 400),
    BLOCK_SELF("BLOCK_SELF", "나 자신은 차단할 수 없습니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    ModerationErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int status() {
        return status;
    }
}
