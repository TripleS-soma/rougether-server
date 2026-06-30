package com.triples.rougether.userapi.todo.error;

import com.triples.rougether.common.error.ErrorCode;

public enum TodoErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    TODO_NOT_FOUND("TODO_NOT_FOUND", "투두를 찾을 수 없습니다.", 404),
    TODO_ALREADY_COMPLETED("TODO_ALREADY_COMPLETED", "이미 완료한 투두입니다.", 409),
    TODO_NOT_COMPLETED("TODO_NOT_COMPLETED", "완료하지 않은 투두입니다.", 409),
    TODO_NOT_CANCELABLE("TODO_NOT_CANCELABLE", "당일 완료만 취소할 수 있습니다.", 409),
    // 회수 정책 확정 전 음수 잔액만 막는 방어적 가드(재화 도메인 합의 시 재검토)
    WALLET_INSUFFICIENT("WALLET_INSUFFICIENT", "잔액이 부족해 완료를 취소할 수 없습니다.", 409),
    WALLET_NOT_FOUND("WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    TodoErrorCode(String code, String message, int status) {
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
