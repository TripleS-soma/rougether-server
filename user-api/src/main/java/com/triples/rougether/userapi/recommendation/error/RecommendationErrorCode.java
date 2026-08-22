package com.triples.rougether.userapi.recommendation.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RecommendationErrorCode implements ErrorCode {

    // 타인의 추천 id 도 존재 여부를 노출하지 않기 위해 같은 404 를 쓴다.
    RECOMMENDATION_NOT_FOUND("RECOMMENDATION_NOT_FOUND", "추천을 찾을 수 없습니다.", 404),
    RECOMMENDATION_ALREADY_HANDLED("RECOMMENDATION_ALREADY_HANDLED", "이미 처리된 추천입니다.", 409),
    RECOMMENDATION_EXPIRED("RECOMMENDATION_EXPIRED", "만료된 추천입니다.", 409),
    RECOMMENDATION_ROUTINE_DELETED("RECOMMENDATION_ROUTINE_DELETED", "추천 대상 루틴이 삭제되어 적용할 수 없습니다.", 409),
    // 추천 생성 뒤 사용자가 스케줄을 먼저 수정해 버전이 갈린 경우 — 사용자가 손댄 스케줄을 덮어쓰지 않는다
    RECOMMENDATION_STALE("RECOMMENDATION_STALE", "루틴 스케줄이 이미 변경되어 추천을 적용할 수 없습니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    RecommendationErrorCode(String code, String message, int status) {
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
