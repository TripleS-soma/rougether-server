package com.triples.rougether.adminapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

class AdminSessionConfigTest {

    private final AdminSessionConfig config = new AdminSessionConfig();

    @Test
    void 운영_쿠키_설정을_Spring_Session에_반영한다() {
        String setCookie = writeCookie(config.adminSessionCookieSerializer(true, true, "strict"));

        assertThat(setCookie)
                .contains("JSESSIONID=", "Path=/", "HttpOnly", "Secure", "SameSite=strict");
    }

    @Test
    void 로컬_HTTP에서는_Secure_속성을_강제하지_않는다() {
        String setCookie = writeCookie(config.adminSessionCookieSerializer(false, true, "strict"));

        assertThat(setCookie)
                .contains("JSESSIONID=", "Path=/", "HttpOnly", "SameSite=strict")
                .doesNotContain("Secure");
    }

    private String writeCookie(CookieSerializer serializer) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieSerializer.CookieValue cookieValue = new CookieSerializer.CookieValue(
                new MockHttpServletRequest(), response, "session-id");
        serializer.writeCookieValue(cookieValue);
        return response.getHeader("Set-Cookie");
    }
}
