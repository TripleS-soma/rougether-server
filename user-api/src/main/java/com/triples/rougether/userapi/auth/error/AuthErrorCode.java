package com.triples.rougether.userapi.auth.error;

import com.triples.rougether.common.error.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

    USER_NOT_FOUND("AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다.", 404),
    INVALID_TOKEN("AUTH_INVALID_TOKEN", "인증 토큰이 유효하지 않습니다.", 401),
    REFRESH_TOKEN_INVALID("AUTH_REFRESH_TOKEN_INVALID", "refresh 토큰이 유효하지 않습니다.", 401),
    OAUTH_KAKAO_TOKEN_INVALID("AUTH_OAUTH_KAKAO_TOKEN_INVALID", "카카오 토큰이 유효하지 않습니다.", 401),
    OAUTH_KAKAO_UNAVAILABLE("AUTH_OAUTH_KAKAO_UNAVAILABLE", "카카오 인증 서버 응답에 실패했습니다.", 502),
    OAUTH_GOOGLE_TOKEN_INVALID("AUTH_OAUTH_GOOGLE_TOKEN_INVALID", "구글 토큰이 유효하지 않습니다.", 401),
    OAUTH_GOOGLE_UNAVAILABLE("AUTH_OAUTH_GOOGLE_UNAVAILABLE", "구글 인증 서버 응답에 실패했습니다.", 502),
    FORBIDDEN("AUTH_FORBIDDEN", "접근 권한이 없습니다.", 403);

    private final String code;
    private final String message;
    private final int status;

    AuthErrorCode(String code, String message, int status) {
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
