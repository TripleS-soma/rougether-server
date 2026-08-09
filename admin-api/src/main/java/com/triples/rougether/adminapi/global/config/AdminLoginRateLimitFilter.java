package com.triples.rougether.adminapi.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

// 공개 관리자 로그인에 대한 IP별 고정 윈도우 제한. CloudFront Function이 viewer-request 단계에서
// X-Rougether-Viewer-Ip를 실제 접속 IP로 덮어쓰므로 임의 X-Forwarded-For는 신뢰하지 않음.
final class AdminLoginRateLimitFilter extends OncePerRequestFilter {

    static final String VIEWER_IP_HEADER = "X-Rougether-Viewer-Ip";
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    private static final int DEFAULT_MAX_TRACKED_IPS = 10_000;

    private final Clock clock;
    private final int maxAttempts;
    private final Duration windowDuration;
    private final int maxTrackedIps;
    private final Map<String, AttemptWindow> attempts = new HashMap<>();
    private final PriorityQueue<Expiry> expirations = new PriorityQueue<>(Comparator.comparing(Expiry::expiresAt));

    AdminLoginRateLimitFilter() {
        this(Clock.systemUTC(), DEFAULT_MAX_ATTEMPTS, DEFAULT_WINDOW, DEFAULT_MAX_TRACKED_IPS);
    }

    AdminLoginRateLimitFilter(Clock clock, int maxAttempts, Duration windowDuration) {
        this(clock, maxAttempts, windowDuration, DEFAULT_MAX_TRACKED_IPS);
    }

    AdminLoginRateLimitFilter(Clock clock, int maxAttempts, Duration windowDuration, int maxTrackedIps) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.windowDuration = windowDuration;
        this.maxTrackedIps = maxTrackedIps;
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
        AttemptWindow attemptWindow = recordAttempt(clientIp(request), now);

        if (attemptWindow == null || attemptWindow.count() > maxAttempts) {
            Instant retryAt = attemptWindow == null ? earliestExpiry() : attemptWindow.expiresAt();
            long retryAfter = Math.max(1, Duration.between(now, retryAt).toSeconds());
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

    private synchronized AttemptWindow recordAttempt(String clientIp, Instant now) {
        removeExpired(now);

        AttemptWindow current = attempts.get(clientIp);
        if (current != null) {
            AttemptWindow updated = new AttemptWindow(current.expiresAt(), current.count() + 1);
            attempts.put(clientIp, updated);
            return updated;
        }

        if (attempts.size() >= maxTrackedIps) {
            return null;
        }

        AttemptWindow created = new AttemptWindow(now.plus(windowDuration), 1);
        attempts.put(clientIp, created);
        expirations.add(new Expiry(clientIp, created.expiresAt()));
        return created;
    }

    private synchronized Instant earliestExpiry() {
        Expiry expiry = expirations.peek();
        return expiry == null ? clock.instant().plus(windowDuration) : expiry.expiresAt();
    }

    private void removeExpired(Instant now) {
        while (!expirations.isEmpty() && !expirations.peek().expiresAt().isAfter(now)) {
            Expiry expiry = expirations.poll();
            AttemptWindow current = attempts.get(expiry.clientIp());
            if (current != null && current.expiresAt().equals(expiry.expiresAt())) {
                attempts.remove(expiry.clientIp());
            }
        }
    }

    private record AttemptWindow(Instant expiresAt, int count) {
    }

    private record Expiry(String clientIp, Instant expiresAt) {
    }
}
