package com.triples.rougether.userapi.activity.filter;

import com.triples.rougether.userapi.global.security.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.LongConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class UserDailyActivityFilter extends OncePerRequestFilter {

    private final LongConsumer recordAction;

    public UserDailyActivityFilter(LongConsumer recordAction) {
        this.recordAction = recordAction;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthUser authUser
                && authUser.id() != null) {
            try {
                // HTTP 결과와 무관하게 유효 JWT로 성립한 user-api 요청 자체를 활동으로 기록함.
                recordAction.accept(authUser.id());
            } catch (RuntimeException e) {
                // recorder 밖에서 발생한 예외까지 차단해 관측이 원 요청을 깨뜨리지 않게 함.
                log.warn("일별 사용자 활동 필터 기록에 실패했습니다. userId={}", authUser.id(), e);
            }
        }
        chain.doFilter(request, response);
    }
}
