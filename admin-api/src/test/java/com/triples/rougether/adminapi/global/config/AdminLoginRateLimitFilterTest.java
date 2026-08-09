package com.triples.rougether.adminapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminLoginRateLimitFilterTest {

    @Test
    void 같은_viewer_IP의_로그인_시도가_한도를_넘으면_429를_반환한다() throws Exception {
        AdminLoginRateLimitFilter filter = new AdminLoginRateLimitFilter(
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC),
                2,
                Duration.ofMinutes(15)
        );

        assertThat(login(filter, "198.51.100.1, 203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(login(filter, "198.51.100.1, 203.0.113.7").getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = login(filter, "198.51.100.1, 203.0.113.7");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("900");
    }

    @Test
    void 서로_다른_viewer_IP는_별도_한도로_계산한다() throws Exception {
        AdminLoginRateLimitFilter filter = new AdminLoginRateLimitFilter(
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC),
                1,
                Duration.ofMinutes(15)
        );

        assertThat(login(filter, "203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(login(filter, "203.0.113.8").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse login(AdminLoginRateLimitFilter filter, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
