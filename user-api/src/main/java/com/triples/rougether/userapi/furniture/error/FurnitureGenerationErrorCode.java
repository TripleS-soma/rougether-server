package com.triples.rougether.userapi.furniture.error;

import com.triples.rougether.common.error.ErrorCode;

public enum FurnitureGenerationErrorCode implements ErrorCode {
    FURNITURE_GENERATION_UNAVAILABLE("사진으로 가구 만들기를 현재 사용할 수 없습니다.", 503),
    FURNITURE_PHOTO_INVALID("10MB 이하의 JPEG 또는 PNG 사진을 올려 주세요.", 400),
    FURNITURE_JOB_NOT_FOUND("가구 생성 작업을 찾을 수 없습니다.", 404),
    FURNITURE_REQUEST_CONFLICT("같은 요청 ID에 다른 내용을 사용할 수 없습니다.", 409),
    FURNITURE_JOB_IN_PROGRESS("이미 가구를 만들고 있습니다. 완료 후 다시 시도해 주세요.", 409),
    FURNITURE_DAILY_LIMIT("오늘의 가구 생성 횟수를 모두 사용했습니다.", 429),
    FURNITURE_FEEDBACK_UNAVAILABLE("완료된 가구만 다시 검토할 수 있습니다.", 409),
    FURNITURE_SOURCE_EXPIRED("사진 보관 기간이 끝났습니다. 새 사진으로 다시 만들어 주세요.", 410),
    FURNITURE_BUDGET_EXHAUSTED("이 가구의 재시도 횟수를 모두 사용했습니다.", 409);

    private final String message;
    private final int status;
    FurnitureGenerationErrorCode(String message, int status) { this.message = message; this.status = status; }
    @Override public String code() { return name(); }
    @Override public String message() { return message; }
    @Override public int status() { return status; }
}
