package com.triples.rougether.userapi.global.observability;

import io.sentry.Sentry;
import org.springframework.stereotype.Component;

// SENTRY_DSN 이 없으면 Sentry SDK 자체가 비활성이라 captureException 은 아무것도 하지 않음(로컬·테스트 무영향).
@Component
public class SentryErrorTracker implements ErrorTracker {

    @Override
    public void captureServerError(String endpoint, Throwable cause) {
        if (!Sentry.isEnabled()) {
            return;
        }
        Sentry.withScope(scope -> {
            scope.setTag("endpoint", endpoint);
            Sentry.captureException(cause);
        });
    }
}
