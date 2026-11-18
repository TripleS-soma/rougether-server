package com.triples.rougether.userapi.todo.error;

import com.triples.rougether.common.error.ErrorCode;

public enum TodoErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    TODO_NOT_FOUND("TODO_NOT_FOUND", "투두를 찾을 수 없습니다.", 404),
    TODO_ALREADY_COMPLETED("TODO_ALREADY_COMPLETED", "이미 완료한 투두입니다.", 409),
    TODO_NOT_COMPLETED("TODO_NOT_COMPLETED", "완료하지 않은 투두입니다.", 409),
    // 반복 루틴 전환은 미완료 투두만 — 완료 보상이 남은 채 같은 날 루틴 보상을 또 받지 않게 함
    TODO_COMPLETED_NOT_CONVERTIBLE("TODO_COMPLETED_NOT_CONVERTIBLE",
            "완료한 투두는 반복 루틴으로 바꿀 수 없습니다. 완료를 취소한 뒤 다시 시도해 주세요.", 409),
    TODO_FUTURE_NOT_COMPLETABLE("TODO_FUTURE_NOT_COMPLETABLE", "마감일이 미래인 투두는 완료할 수 없습니다.", 400),
    WALLET_NOT_FOUND("WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404),
    // 기기 캘린더 임포트: 같은 (externalSource, externalId)를 이미 가져옴 — 앱은 건너뛴다(soft delete 된 것도 포함)
    TODO_EXTERNAL_DUPLICATE("TODO_EXTERNAL_DUPLICATE", "이미 가져온 일정입니다.", 409),
    TODO_EXTERNAL_REF_INCOMPLETE("TODO_EXTERNAL_REF_INCOMPLETE",
            "externalSource와 externalId는 함께 보내야 합니다.", 400);

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
