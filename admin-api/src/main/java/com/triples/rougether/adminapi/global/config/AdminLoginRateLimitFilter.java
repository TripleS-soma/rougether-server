package com.triples.rougether.adminapi.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

// 공개 관리자 로그인에 대한 IP별 고정 윈도우 제한. CloudFront Function이 viewer-request 단계에서
// X-Rougether-Viewer-Ip를 실제 접속 IP로 덮어쓰므로 임의 X-Forwarded-For는 신뢰하지 않음.
final class AdminLoginRateLimitFilter extends OncePerRequestFilter {

    static final String VIEWER_IP_HEADER = "X-Rougether-Viewer-Ip";
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final int maxAttempts;
    private final Duration windowDuration;
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    AdminLoginRateLimitFilter() {
        this(Clock.systemUTC(), DEFAULT_MAX_ATTEMPTS, DEFAULT_WINDOW);
    }

    AdminLoginRateLimitFilter(Clock clock, int maxAttempts, Duration windowDuration) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.windowDuration = windowDuration;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !"/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Instant now = clock.instant();
        cleanupExpiredWindows(now);

        AttemptWindow attemptWindow = attempts.compute(clientIp(request), (ignored, current) -> {
            if (current == null || current.startedAt().plus(windowDuration).isBefore(now)) {
                return new AttemptWindow(now, 1);
            }
            return new AttemptWindow(current.startedAt(), current.count() + 1);
        });

        if (attemptWindow.count() > maxAttempts) {
            long retryAfter = Math.max(1, Duration.between(now, attemptWindow.startedAt().plus(windowDuration)).toSeconds());
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String viewerIp = request.getHeader(VIEWER_IP_HEADER);
        return viewerIp == null || viewerIp.isBlank() ? request.getRemoteAddr() : viewerIp.trim();
    }

    private void cleanupExpiredWindows(Instant now) {
        if (attempts.size() < CLEANUP_THRESHOLD) {
            return;
        }
        attempts.entrySet().removeIf(entry -> entry.getValue().startedAt().plus(windowDuration).isBefore(now));
    }

    private record AttemptWindow(Instant startedAt, int count) {
    }
}
