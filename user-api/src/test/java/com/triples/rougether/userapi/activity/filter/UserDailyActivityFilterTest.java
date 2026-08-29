package com.triples.rougether.userapi.activity.filter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.MemberRole;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class UserDailyActivityFilterTest {

    @Mock
    private UserDailyActivityRecorder recorder;
    @Mock
    private FilterChain chain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_요청은_응답_상태와_무관하게_사용자_활동을_기록한다() throws Exception {
        authenticate(7L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(invocation -> {
            response.setStatus(500);
            return null;
        }).when(chain).doFilter(request, response);

        new UserDailyActivityFilter(recorder::record).doFilter(request, response, chain);

        verify(recorder).record(7L);
        verify(chain).doFilter(request, response);
    }

    @Test
    void 비인증_요청은_활동을_기록하지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserDailyActivityFilter(recorder::record).doFilter(request, response, chain);

        verify(recorder, never()).record(org.mockito.ArgumentMatchers.anyLong());
        verify(chain).doFilter(request, response);
    }

    @Test
    void 기록기가_실패해도_원래_요청은_계속_처리한다() throws Exception {
        authenticate(7L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new IllegalStateException("database unavailable")).when(recorder).record(7L);

        assertThatCode(() -> new UserDailyActivityFilter(recorder::record)
                .doFilter(request, response, chain))
                .doesNotThrowAnyException();

        verify(chain).doFilter(request, response);
    }

    private void authenticate(Long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(
                new AuthUser(userId, MemberRole.NORMAL), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
