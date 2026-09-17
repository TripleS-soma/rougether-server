package com.triples.rougether.userapi.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void 요청_ID가_없으면_새로_만들어_응답_헤더와_MDC에_남기고_요청이_끝나면_MDC를_비운다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotBlank();
        assertThat(seenInChain.get()).isEqualTo(header);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 안전한_형식의_요청_ID는_이어받는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.HEADER, "app-8f3c2a91");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("app-8f3c2a91");
    }

    @Test
    void 줄바꿈이나_과도한_길이의_요청_ID는_버리고_새로_만든다() {
        assertThat(RequestIdFilter.resolve("abc\ninjected-log-line")).isNotEqualTo("abc\ninjected-log-line");
        assertThat(RequestIdFilter.resolve("short")).isNotEqualTo("short");
        assertThat(RequestIdFilter.resolve("x".repeat(65))).hasSize(36);
    }
}
