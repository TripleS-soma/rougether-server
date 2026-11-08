package com.triples.rougether.userapi.logingift.filter;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class LoginGiftAccessFilter extends OncePerRequestFilter {
    private final LoginGiftAccessRecorder recorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthUser user && user.id() != null) {
            try {
                recorder.record(user.id());
            } catch (BusinessException e) {
                // 접속 기록이 없는 요청을 성공으로 숨기지 않음. 지급 실패는 recorder가 대기 처리함.
                response.setStatus(503);
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Retry-After", "1");
                response.getWriter().write("{\"code\":\"LOGIN_GIFT_CAPTURE_UNAVAILABLE\","
                        + "\"message\":\"접속 선물 참여 기록을 저장하지 못했습니다. 다시 시도해 주세요.\",\"fieldErrors\":[]}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
