package com.triples.rougether.batch.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// user-api WebexOperationalAlertNotifier 와 같은 env(OPERATIONS_WEBEX_BOT_TOKEN/ROOM_ID)·payload 형식을 쓰는 batch 알림기.
// 토큰이나 room 이 비면 호출하지 않음. 전송은 비동기이고 실패는 로그만 남김
@Slf4j
@Component
public class WebexBatchFailureAlertNotifier implements BatchFailureAlertNotifier {

    private static final URI MESSAGES_URI = URI.create("https://webexapis.com/v1/messages");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_FIELD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String botToken;
    private final String roomId;
    private final String environment;
    private final Duration cooldown;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    @Autowired
    public WebexBatchFailureAlertNotifier(
            ObjectMapper objectMapper,
            @Value("${operations.webex.bot-token:}") String botToken,
            @Value("${operations.webex.room-id:}") String roomId,
            @Value("${operations.webex.environment:dev}") String environment,
            @Value("${operations.webex.cooldown:PT6H}") Duration cooldown) {
        this(objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                botToken, roomId, environment, cooldown, Clock.systemUTC());
    }

    WebexBatchFailureAlertNotifier(ObjectMapper objectMapper, HttpClient httpClient, String botToken,
                                   String roomId, String environment, Duration cooldown, Clock clock) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.botToken = botToken == null ? "" : botToken.strip();
        this.roomId = roomId == null ? "" : roomId.strip();
        this.environment = sanitize(environment, 30).toUpperCase(Locale.ROOT);
        this.cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
        this.clock = clock;
    }

    @Override
    public void notifyFailure(String key, String title, String detail) {
        if (botToken.isBlank() || roomId.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        if (!acquireCooldown(key, now)) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(MESSAGES_URI)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(title, detail, now)))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .orTimeout(REQUEST_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS)
                    .whenComplete((response, failure) -> logDelivery(key, response, failure));
        } catch (RuntimeException exception) {
            // 전송 자체를 못 했으면 다음 시도에서 다시 보낼 수 있게 cooldown 선점을 되돌림
            lastSentAt.remove(key, now);
            log.warn("Webex batch alert dispatch failed: {}", exception.getClass().getSimpleName());
        }
    }

    private void logDelivery(String key, HttpResponse<Void> response, Throwable failure) {
        if (failure != null) {
            log.warn("Webex batch alert delivery failed: {}", failure.getClass().getSimpleName());
        } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Webex batch alert returned status={}", response.statusCode());
        } else {
            log.info("Webex batch alert delivered: key={}", key);
        }
    }

    private boolean acquireCooldown(String key, Instant now) {
        while (true) {
            Instant previous = lastSentAt.putIfAbsent(key, now);
            if (previous == null) {
                return true;
            }
            if (previous.plus(cooldown).isAfter(now)) {
                return false;
            }
            if (lastSentAt.replace(key, previous, now)) {
                return true;
            }
        }
    }

    String buildPayload(String title, String detail, Instant occurredAt) {
        String markdown = "**[%s] batch ERROR**\n\n작업: %s  \n내용: %s  \n시각: %s"
                .formatted(
                        environment,
                        sanitize(title, 100),
                        sanitize(detail, MAX_FIELD_LENGTH),
                        DateTimeFormatter.ISO_INSTANT.format(occurredAt));
        return objectMapper.writeValueAsString(Map.of(
                "roomId", roomId,
                "markdown", markdown));
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "-";
        }
        String sanitized = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('`', '\'')
                .replace('@', '＠')
                .strip();
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        return sanitized.substring(0, maxLength - 1) + "…";
    }
}
