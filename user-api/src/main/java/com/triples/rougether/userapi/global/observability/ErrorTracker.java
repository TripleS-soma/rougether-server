package com.triples.rougether.userapi.global.observability;

// 예상치 못한 서버 오류를 외부 에러 추적 도구(Sentry)로 보내는 창구.
// 전역 예외 처리기가 처리해 응답으로 바꾼 예외는 Sentry 기본 리졸버에 닿지 않으므로 여기서 명시적으로 보냄.
// 비즈니스 4xx 는 정상 흐름이라 보내지 않음(호출 쪽에서 5xx·미처리 예외만 부름).
public interface ErrorTracker {

    ErrorTracker NO_OP = (endpoint, cause) -> {
    };

    void captureServerError(String endpoint, Throwable cause);
}
