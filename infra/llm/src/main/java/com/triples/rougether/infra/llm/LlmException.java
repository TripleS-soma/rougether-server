package com.triples.rougether.infra.llm;

// LLM 호출 실패. retryable=true(429·5xx·네트워크)는 클라이언트가 이미 재시도를 소진한 뒤 던지는 값이며,
// 호출자는 이 플래그로 "일시 장애 → 폴백" / "요청 자체 문제(4xx) → 폴백+경고" 정도의 구분만 한다.
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
