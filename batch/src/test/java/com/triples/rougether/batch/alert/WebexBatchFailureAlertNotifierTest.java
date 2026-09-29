package com.triples.rougether.batch.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WebexBatchFailureAlertNotifierTest {

    private static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");
    private static final Duration COOLDOWN = Duration.ofHours(6);
    private static final String KEY = "routineDayEndJob|2026-09-28";

    private final HttpClient httpClient = mock(HttpClient.class);
    private final Clock clock = mock(Clock.class);

    @BeforeEach
    void stubHttpAndClock() {
        doReturn(NOW).when(clock).instant();
        doReturn(new CompletableFuture<HttpResponse<Void>>())
                .when(httpClient).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void 설정이_비어_있으면_호출하지_않는다() {
        notifier("", "room").notifyFailure(KEY, "하루 마감 실패", "targetDate=2026-09-28");
        notifier("token", " ").notifyFailure(KEY, "하루 마감 실패", "targetDate=2026-09-28");

        verify(httpClient, never()).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void 같은_키는_cooldown_동안_한_번만_보내고_지나면_다시_보낸다() {
        WebexBatchFailureAlertNotifier notifier = notifier("token", "room");

        notifier.notifyFailure(KEY, "하루 마감 실패", "1회차");
        // 매시 정각 재시도 - cooldown 안
        doReturn(NOW.plus(Duration.ofHours(1))).when(clock).instant();
        notifier.notifyFailure(KEY, "하루 마감 실패", "2회차");
        verify(httpClient, times(1)).sendAsync(any(HttpRequest.class), any());

        doReturn(NOW.plus(COOLDOWN)).when(clock).instant();
        notifier.notifyFailure(KEY, "하루 마감 실패", "cooldown 이후");
        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void 다른_키는_cooldown_과_무관하게_보낸다() {
        WebexBatchFailureAlertNotifier notifier = notifier("token", "room");

        notifier.notifyFailure(KEY, "하루 마감 실패", "a");
        notifier.notifyFailure("routineDayEndJob|2026-09-29", "하루 마감 실패", "b");

        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void 전송_호출이_실패해도_예외를_던지지_않고_다음_시도에서_다시_보낸다() {
        WebexBatchFailureAlertNotifier notifier = notifier("token", "room");
        doThrow(new IllegalStateException("network"))
                .when(httpClient).sendAsync(any(HttpRequest.class), any());

        assertThatCode(() -> notifier.notifyFailure(KEY, "하루 마감 실패", "a")).doesNotThrowAnyException();

        doReturn(new CompletableFuture<HttpResponse<Void>>())
                .when(httpClient).sendAsync(any(HttpRequest.class), any());
        notifier.notifyFailure(KEY, "하루 마감 실패", "b");
        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void payload는_대상_스페이스를_지정하고_멘션과_토큰을_노출하지_않는다() {
        String payload = notifier("test-bot-token", "test-room-id")
                .buildPayload("하루 마감 실패", "@all `targetDate`", NOW);

        assertThat(payload)
                .contains("\"roomId\":\"test-room-id\"")
                .contains("[DEV] batch ERROR")
                .contains("＠all")
                .contains("'targetDate'")
                .doesNotContain("@all")
                .doesNotContain("test-bot-token");
    }

    private WebexBatchFailureAlertNotifier notifier(String botToken, String roomId) {
        return new WebexBatchFailureAlertNotifier(
                new ObjectMapper(), httpClient, botToken, roomId, "dev", COOLDOWN, clock);
    }
}
