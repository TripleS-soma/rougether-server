package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

// 전체 스택(보안 필터·실제 MySQL(Testcontainers)·Flyway)에서 인증 가드와 refresh 회전·유예·재사용(family 단위)을 검증함.
@SpringBootTest
@AutoConfigureMockMvc
class AuthSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AuthService authService;

    @Test
    void 토큰_없이_보호자원_접근하면_401_과_code_를_준다() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }

    @Test
    void 토큰_없이_회원탈퇴를_요청하면_401_과_code_를_준다() throws Exception {
        mockMvc.perform(delete("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8081", "http://127.0.0.1:8081", "https://app.rougether.com"})
    void 허용된_origin의_preflight_요청은_CORS_허용된다(String origin) throws Exception {
        mockMvc.perform(options("/api/v1/me")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://app.rougether.com", "https://app.rougether.com.example.com"})
    void 허용되지_않은_origin의_preflight_요청은_거부된다(String origin) throws Exception {
        mockMvc.perform(options("/api/v1/me")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void 웹앱에서_토큰_없이_보호자원에_접근하면_CORS_헤더와_401을_준다() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.ORIGIN, "https://app.rougether.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.rougether.com"))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }

    @Test
    void 유효한_access_토큰으로_me_조회와_refresh_회전_재사용을_검증한다() throws Exception {
        User user = userRepository.save(User.signUp());
        user.recordAccess(Instant.now());
        userRepository.save(user);
        Long userId = user.getId();

        String accessToken = tokenService.issueAccessToken(userId, MemberRole.NORMAL);
        GeneratedRefreshToken firstRefresh = tokenService.generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(user, firstRefresh.tokenHash(), firstRefresh.expiresAt()));
        String r1 = firstRefresh.raw();

        // 1) 유효 토큰으로 me 200
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId));

        // 2) refresh 회전 → 새 쌍 r2
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String r2 = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.refreshToken");
        assertThat(r2).isNotBlank().isNotEqualTo(r1);

        // 3) 회전된 지 60초 안의 r1 재제출 = 응답 유실 재시도 → 200, 새 쌍 r3. 받지 못한 r2 는 밀려남(SUPERSEDED)
        String r3 = refreshOk(r1);
        assertThat(r3).isNotEqualTo(r2);
        expectSuperseded(r2);

        // 4) 같은 회원의 다른 기기(별도 로그인 = 별도 family)
        GeneratedRefreshToken otherDevice = tokenService.generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(user, otherDevice.tokenHash(), otherDevice.expiresAt()));

        // 5) 유예(60초)가 지난 r1 재사용 → 401, 그 기기(family)의 r3 까지 폐기됨
        backdateRevokedAt(r1, Duration.ofMinutes(2));
        expectRefreshInvalid(r1);
        expectRefreshInvalid(r3);

        // 6) 다른 기기 토큰은 살아있음
        refreshOk(otherDevice.raw());
    }

    @Test
    void 같은_refresh_를_동시에_두_번_보내도_둘_다_성공하고_family_에는_살아있는_토큰이_하나만_남는다() throws Exception {
        User user = userRepository.save(User.signUp());
        GeneratedRefreshToken first = tokenService.generateRefreshToken();
        RefreshToken firstToken = refreshTokenRepository.save(
                RefreshToken.issue(user, first.tokenHash(), first.expiresAt()));
        String familyId = firstToken.getFamilyId();
        GeneratedRefreshToken otherDevice = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, otherDevice.tokenHash(), otherDevice.expiresAt()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TokenResponse>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return authService.refresh(first.raw());
                }));
            }
            start.countDown();
            // 예전에는 진 쪽이 재사용으로 판정돼 회원 토큰 전체가 폐기됐음. 이제 줄을 서서 뒤 요청이 유예 재발급을 받음.
            for (Future<TokenResponse> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).refreshToken()).isNotBlank();
            }
        } finally {
            pool.shutdownNow();
        }

        Integer activeInFamily = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Integer.class, familyId);
        assertThat(activeInFamily).isEqualTo(1);
        refreshOk(otherDevice.raw());
    }

    @Test
    void 로그아웃한_refresh_를_다시_보내면_그_family_만_폐기되고_다른_기기는_유지된다() throws Exception {
        User user = userRepository.save(User.signUp());
        GeneratedRefreshToken device = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, device.tokenHash(), device.expiresAt()));
        GeneratedRefreshToken otherDevice = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, otherDevice.tokenHash(), otherDevice.expiresAt()));

        String rotated = refreshOk(device.raw());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rotated + "\"}"))
                .andExpect(status().is2xxSuccessful());

        // 로그아웃 사유는 유예 대상이 아님 → 방금이어도 401
        expectRefreshInvalid(rotated);
        refreshOk(otherDevice.raw());
    }

    @Test
    void 레거시_family_없는_폐기_토큰_재사용은_회원_토큰을_전부_폐기한다() throws Exception {
        User user = userRepository.save(User.signUp());
        GeneratedRefreshToken legacy = tokenService.generateRefreshToken();
        RefreshToken legacyToken = RefreshToken.issueInFamily(user, legacy.tokenHash(), legacy.expiresAt(), null);
        legacyToken.revoke(Instant.now(), null);
        refreshTokenRepository.save(legacyToken);
        GeneratedRefreshToken otherDevice = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, otherDevice.tokenHash(), otherDevice.expiresAt()));

        expectRefreshInvalid(legacy.raw());
        expectRefreshInvalid(otherDevice.raw());
    }

    private String refreshOk(String raw) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");
    }

    private void expectRefreshInvalid(String raw) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + raw + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    // 유예 재발급이 받지 못한 후속 토큰을 SUPERSEDED 로 밀어냈는지 확인함.
    private void expectSuperseded(String raw) {
        Integer revoked = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ? and revoke_reason = 'SUPERSEDED'",
                Integer.class, tokenService.hashRefreshToken(raw));
        assertThat(revoked).isEqualTo(1);
    }

    // 시간대 변환을 피하려고 DB 안에서 상대값으로 당김(Instant 저장 시간대와 JDBC Timestamp 시간대가 다를 수 있음).
    private void backdateRevokedAt(String raw, Duration ago) {
        jdbcTemplate.update("update refresh_tokens set revoked_at = revoked_at - INTERVAL ? SECOND where token_hash = ?",
                ago.toSeconds(), tokenService.hashRefreshToken(raw));
    }

    @Test
    void refresh_정상_회전은_last_accessed_at_을_갱신하고_revoke_경로는_갱신하지_않는다() throws Exception {
        User user = userRepository.save(User.signUp());
        // TIMESTAMP 초 단위 정밀도 영향을 받지 않도록 과거 시각으로 시드함.
        Instant seeded = Instant.now().minus(Duration.ofDays(1));
        user.recordAccess(seeded);
        userRepository.save(user);
        Long userId = user.getId();

        GeneratedRefreshToken firstRefresh = tokenService.generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(user, firstRefresh.tokenHash(), firstRefresh.expiresAt()));
        String r1 = firstRefresh.raw();

        // 1) 정상 회전 → 회전과 같은 트랜잭션에서 last_accessed_at 이 갱신됨
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String r2 = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.refreshToken");
        Instant afterRotate = userRepository.findById(userId).orElseThrow().getLastAccessedAt();
        assertThat(afterRotate).isAfter(seeded);

        // 2) 유예가 지난 r1 재사용(reuse 감지→family 폐기) → 401, last_accessed_at 미갱신
        backdateRevokedAt(r1, Duration.ofMinutes(2));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findById(userId).orElseThrow().getLastAccessedAt())
                .isEqualTo(afterRotate);

        // 3) reuse 감지로 폐기된 r2 → 401, last_accessed_at 미갱신
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r2 + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findById(userId).orElseThrow().getLastAccessedAt())
                .isEqualTo(afterRotate);

        // 4) 과거 시각으로의 역행 UPDATE 는 무시된다(여러 기기 동시 회전 시 최신 값 보호)
        transactionTemplate.executeWithoutResult(tx ->
                userRepository.updateLastAccessedAt(userId, seeded));
        assertThat(userRepository.findById(userId).orElseThrow().getLastAccessedAt())
                .isEqualTo(afterRotate);
    }
}
