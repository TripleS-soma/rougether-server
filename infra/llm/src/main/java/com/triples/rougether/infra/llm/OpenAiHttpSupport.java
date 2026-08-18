package com.triples.rougether.infra.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// OpenAI 호환 클라이언트(chat/completions·embeddings)가 공유하는 RestClient 생성·재시도·예외 분류.
// 채팅·임베딩 클라이언트의 동작을 한 곳에서 맞추기 위한 패키지 전용 헬퍼.
final class OpenAiHttpSupport {

    private OpenAiHttpSupport() {
    }

    static RestClient restClient(LlmProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    // 429/5xx/네트워크 오류는 llm.max-retries 회 지수 백오프로 재시도, 그 외 4xx(retryable=false)는 즉시 실패.
    static <T> T withRetry(LlmProperties properties, Consumer<Duration> sleeper, Logger log, String what,
                           Supplier<T> call) {
        int maxAttempts = properties.maxRetries() + 1;
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (LlmException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
                if (attempt < maxAttempts) {
                    Duration backoff = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
                    log.warn("{} 호출 실패(시도 {}/{}) — {}ms 후 재시도: {}", what, attempt, maxAttempts,
                            backoff.toMillis(), e.getMessage());
                    sleeper.accept(backoff);
                }
            }
        }
        throw last;
    }

    // HTTP 오류를 LlmException 계열로 분류: 401/403 → LlmAuthException(설정 장애), 429/5xx → retryable, 그 외 4xx → 즉시 실패
    static LlmException translate(RestClientException e, String what) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401 || status == 403) {
                // 키 오류·권한 문제는 재시도로도, 폴백으로도 해결되지 않는 설정 장애 — 호출자가 구분해 처리하도록 별도 타입
                return new LlmAuthException(what + " 인증 실패 status=" + status, e);
            }
            boolean retryable = status == 429 || response.getStatusCode().is5xxServerError();
            return new LlmException(what + " 오류 status=" + status, retryable, e);
        }
        return new LlmException(what + " 네트워크 오류: " + e.getMessage(), true, e);
    }

    static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
