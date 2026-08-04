package com.triples.rougether.userapi.global.alert;

import com.triples.rougether.common.error.ErrorCode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebexOperationalAlertNotifier implements OperationalAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebexOperationalAlertNotifier.class);
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
    public WebexOperationalAlertNotifier(
            ObjectMapper objectMapper,
            @Value("${operations.webex.bot-token:}") String botToken,
            @Value("${operations.webex.room-id:}") String roomId,
            @Value("${operations.webex.environment:dev}") String environment,
            @Value("${operations.webex.cooldown:PT1M}") Duration cooldown) {
        this(objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                botToken, roomId, environment, cooldown, Clock.systemUTC());
    }

    WebexOperationalAlertNotifier(ObjectMapper objectMapper, HttpClient httpClient, String botToken,
                                  String roomId, String environment, Duration cooldown, Clock clock) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.botToken = botToken == null ? "" : botToken.strip();
        this.roomId = roomId == null ? "" : roomId.strip();
        this.environment = sanitize(environment, 30).toUpperCase(Locale.ROOT);
        this.cooldown = cooldown.isNegative() ? Duration.ZERO : cooldown;
        this.clock = clock;
    }

    @Override
    public void notifyBusiness(String endpoint, ErrorCode errorCode) {
        if (!shouldNotify(errorCode)) {
            return;
        }

        String level = errorCode.status() >= 500 ? "ERROR" : "WARN";
        send(level, endpoint, errorCode.code(), errorCode.message());
    }

    @Override
    public void notifyUnexpected(String endpoint, Throwable cause) {
        String causeName = cause == null ? "Unknown" : cause.getClass().getSimpleName();
        send("ERROR", endpoint, "INTERNAL_ERROR", causeName);
    }

    boolean shouldNotify(ErrorCode errorCode) {
        return errorCode.status() >= 500 || errorCode.code().startsWith("AUTH_OAUTH_");
    }

    private void send(String level, String endpoint, String code, String message) {
        if (botToken.isBlank() || roomId.isBlank()) {
            return;
        }

        // 같은 종류의 오류가 반복돼도 Webex API rate limit을 소진하지 않게 중복을 억제함.
        // 예기치 않은 오류는 message에 넣은 exception class까지 구분한다.
        String deduplicationKey = level + '|' + code + '|' + message;
        Instant now = clock.instant();
        if (!acquireCooldown(deduplicationKey, now)) {
            return;
        }

        try {
            String payload = buildPayload(level, endpoint, code, message, now);
            HttpRequest request = HttpRequest.newBuilder(MESSAGES_URI)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .orTimeout(REQUEST_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS)
                    .whenComplete((response, failure) -> {
                        if (failure != null) {
                            log.warn("Webex operational alert delivery failed: {}",
                                    failure.getClass().getSimpleName());
                        } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            log.warn("Webex operational alert returned status={}", response.statusCode());
                        } else {
                            log.info("Webex operational alert delivered: code={}", code);
                        }
                    });
        } catch (RuntimeException exception) {
            lastSentAt.remove(deduplicationKey, now);
            log.warn("Webex operational alert dispatch failed: {}", exception.getClass().getSimpleName());
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

    String buildPayload(String level, String endpoint, String code, String message, Instant occurredAt) {
        String markdown = "**[%s] user-api %s**\n\n경로: `%s`  \n코드: `%s`  \n메시지: %s  \n시각: %s"
                .formatted(
                        environment,
                        sanitize(level, 10),
                        sanitize(endpoint, MAX_FIELD_LENGTH),
                        sanitize(code, 100),
                        sanitize(message, MAX_FIELD_LENGTH),
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
