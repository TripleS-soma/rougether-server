package com.triples.rougether.domain.house.entity;

import java.util.Arrays;
import java.util.Optional;

// 원탭 응원 종류. code 는 프론트 CheerType(rougether-mobile friend-room-screen)과 동일 표기로 API·DB 에 소문자 저장.
// message 는 알림 본문 문구("{닉네임}님: {message}").
public enum CheerType {

    GREAT("great", "잘하고 있어!"),
    SUPPORT("support", "응원해요!"),
    BEST("best", "오늘도 최고!");

    private final String code;
    private final String message;

    CheerType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public static Optional<CheerType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }
}
