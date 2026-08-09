package com.triples.rougether.adminapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminOriginVerificationFilterTest {

    private final AdminOriginVerificationFilter filter = new AdminOriginVerificationFilter("expected-secret");

    @Test
    void 외부_요청은_올바른_CloudFront_origin_비밀값이_필요하다() throws Exception {
        MockHttpServletRequest request = externalRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void CloudFront_origin_비밀값이_맞으면_요청을_허용한다() throws Exception {
        MockHttpServletRequest request = externalRequest();
        request.addHeader(AdminOriginVerificationFilter.ORIGIN_HEADER, "expected-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void SSM_비상_터널의_loopback_요청은_비밀값_없이_허용한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void origin_비밀값_설정이_비어_있으면_외부_요청을_차단한다() throws Exception {
        AdminOriginVerificationFilter emptySecretFilter = new AdminOriginVerificationFilter(" ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        emptySecretFilter.doFilter(externalRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest externalRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setRemoteAddr("198.51.100.10");
        return request;
    }
}
