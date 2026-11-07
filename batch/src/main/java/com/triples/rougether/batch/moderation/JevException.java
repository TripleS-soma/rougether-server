package com.triples.rougether.batch.moderation;

// Jev 호출 실패. retryable 은 429·529·5xx·네트워크 오류. 그 외(4xx·형식 오류)는 그 건만 건너뛰고 다음 실행에서 다시 시도됨.
public class JevException extends RuntimeException {

    private final boolean retryable;

    public JevException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public JevException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
