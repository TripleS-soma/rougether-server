package com.triples.rougether.userapi.onboarding.error;

import com.triples.rougether.common.error.ErrorCode;

public enum OnboardingHouseErrorCode implements ErrorCode {
    ONBOARDING_HOUSE_ALREADY_SELECTED;

    public String code() { return name(); }
    public String message() { return "이미 집 선택을 완료했습니다. 이후 변경은 집 탐색·생성·나가기를 이용해주세요."; }
    public int status() { return 409; }
}
