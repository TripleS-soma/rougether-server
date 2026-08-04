package com.triples.rougether.userapi.global.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WebexOperationalAlertNotifierTest {

    private final WebexOperationalAlertNotifier notifier = new WebexOperationalAlertNotifier(
            new ObjectMapper(),
            HttpClient.newHttpClient(),
            "test-bot-token",
            "test-room-id",
            "dev",
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            Clock.fixed(Instant.parse("2026-08-04T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void OAuth_토큰_오류와_서버_오류만_알림_대상이다() {
        assertThat(notifier.shouldNotify(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID)).isTrue();
        assertThat(notifier.shouldNotify(TestErrorCode.INTERNAL)).isTrue();
        assertThat(notifier.shouldNotify(TestErrorCode.NOT_FOUND)).isFalse();
    }

    @Test
    void payload는_대상_스페이스를_지정하고_멘션을_무력화한다() {
        String payload = notifier.buildPayload(
                "WARN",
                "POST /api/v1/auth/google/@all",
                "AUTH_OAUTH_GOOGLE_TOKEN_INVALID",
                "`token` invalid",
                Instant.parse("2026-08-04T08:00:00Z"));

        assertThat(payload)
                .contains("\"roomId\":\"test-room-id\"")
                .contains("＠all")
                .contains("'token' invalid")
                .doesNotContain("@all")
                .doesNotContain("test-bot-token");
    }

    @Test
    void 잘못된_OAuth_토큰은_공급자_전체를_묶어_한_시간_쿨다운을_적용한다() {
        assertThat(notifier.deduplicationGroupFor(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID))
                .isEqualTo("AUTH_OAUTH_TOKEN_INVALID");
        assertThat(notifier.deduplicationGroupFor(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID))
                .isEqualTo("AUTH_OAUTH_TOKEN_INVALID");
        assertThat(notifier.cooldownFor(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID))
                .isEqualTo(Duration.ofHours(1));
        assertThat(notifier.cooldownFor(AuthErrorCode.OAUTH_GOOGLE_UNAVAILABLE))
                .isEqualTo(Duration.ofMinutes(1));
    }

    private enum TestErrorCode implements ErrorCode {
        NOT_FOUND("NOT_FOUND", "없음", 404),
        INTERNAL("INTERNAL", "오류", 500);

        private final String code;
        private final String message;
        private final int status;

        TestErrorCode(String code, String message, int status) {
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
}
