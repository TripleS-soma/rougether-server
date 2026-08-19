package com.triples.rougether.adminapi.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AdminLoginTest {

    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile(
            "name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_META_PATTERN = Pattern.compile(
            "name=\"_csrf\"[^>]*content=\"([^\"]+)\"");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearAdminSessions() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
    }

    @Test
    void 헬스_엔드포인트는_인증없이_접근가능() throws Exception {
        mockMvc.perform(get("/admin/health"))
                .andExpect(status().isOk());
    }

    @Test
    void 시드된_어드민_계정으로_폼로그인_성공() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin").password("admin1234"))
                .andExpect(authenticated().withUsername("admin"));
    }

    @Test
    void 로그인_세션을_JDBC에_저장하고_쿠키로_복원한다() throws Exception {
        Cookie sessionCookie = loginAndGetSessionCookie();

        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class,
                "admin");
        Integer timeoutSeconds = jdbcTemplate.queryForObject(
                "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class,
                "admin");

        mockMvc.perform(get("/").cookie(sessionCookie))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(sessionCount).isPositive();
        org.assertj.core.api.Assertions.assertThat(timeoutSeconds).isEqualTo(1800);
    }

    @Test
    void 로그아웃하면_JDBC_세션을_삭제한다() throws Exception {
        Cookie sessionCookie = loginAndGetSessionCookie();
        MvcResult authenticatedPage = mockMvc.perform(get("/").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(post("/logout")
                        .cookie(sessionCookie)
                        .param("_csrf", extractCsrfToken(
                                authenticatedPage.getResponse().getContentAsString(),
                                CSRF_META_PATTERN)))
                .andExpect(redirectedUrl("/login?logout"));

        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class,
                "admin");
        org.assertj.core.api.Assertions.assertThat(sessionCount).isZero();
    }

    private Cookie loginAndGetSessionCookie() throws Exception {
        MvcResult loginPage = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("JSESSIONID"))
                .andReturn();
        Cookie initialCookie = loginPage.getResponse().getCookie("JSESSIONID");
        // 기본 profile은 로컬 HTTP 개발을 지원하고, mysql profile에서만 Secure를 강제한다.
        org.assertj.core.api.Assertions.assertThat(initialCookie.getSecure()).isFalse();
        org.assertj.core.api.Assertions.assertThat(initialCookie.isHttpOnly()).isTrue();
        org.assertj.core.api.Assertions.assertThat(loginPage.getResponse().getHeader("Set-Cookie"))
                .contains("Path=/", "SameSite=strict");

        MvcResult loginResult = mockMvc.perform(post("/login")
                        .cookie(initialCookie)
                        .param("username", "admin")
                        .param("password", "admin1234")
                        .param("_csrf", extractCsrfToken(
                                loginPage.getResponse().getContentAsString(),
                                CSRF_INPUT_PATTERN)))
                .andExpect(authenticated().withUsername("admin"))
                .andReturn();

        Cookie rotatedCookie = loginResult.getResponse().getCookie("JSESSIONID");
        return rotatedCookie != null ? rotatedCookie : initialCookie;
    }

    private String extractCsrfToken(String html, Pattern pattern) {
        Matcher matcher = pattern.matcher(html);
        org.assertj.core.api.Assertions.assertThat(matcher.find())
                .as("rendered page contains a CSRF token")
                .isTrue();
        return matcher.group(1);
    }

    @Test
    void 잘못된_비밀번호면_인증_실패() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin").password("wrong-password"))
                .andExpect(unauthenticated());
    }
}
