package com.triples.rougether.userapi.global.observability;

import io.sentry.Sentry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 요청마다 X-Request-Id 를 정해 응답 헤더·로그(MDC)·Sentry 태그에 같은 값을 남김.
// 앱 버그 제보(mobile #1162)가 이 값을 첨부하면 서버 로그·Sentry 이벤트와 바로 연결됨.
// 들어온 값은 형식이 안전할 때만 이어받고(로그 주입·과도한 길이 방지), 아니면 새로 만든다.
// Sentry 요청 스코프(SentrySpringFilter, HIGHEST_PRECEDENCE) 뒤에서 태그를 달아야 요청 단위로 격리됨.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        if (Sentry.isEnabled()) {
            Sentry.setTag("request_id", requestId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
