package com.triples.rougether.adminapi.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

// CloudFront 배포가 origin 요청에 주입한 공유 비밀값을 검증함.
// localhost는 CloudFront 장애 시 SSM 포트 포워딩 비상 접근을 위해 허용함.
final class AdminOriginVerificationFilter extends OncePerRequestFilter {

    static final String ORIGIN_HEADER = "X-Rougether-Admin-Origin";

    private final byte[] expectedSecret;

    AdminOriginVerificationFilter(String originSecret) {
        this.expectedSecret = originSecret == null || originSecret.isBlank()
                ? null
                : originSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (expectedSecret == null || isLoopback(request.getRemoteAddr()) || hasValidOriginSecret(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private boolean hasValidOriginSecret(HttpServletRequest request) {
        String actualSecret = request.getHeader(ORIGIN_HEADER);
        return actualSecret != null && MessageDigest.isEqual(
                expectedSecret,
                actualSecret.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isLoopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }
}
