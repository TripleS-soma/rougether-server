package com.triples.rougether.userapi.house;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class HouseCoverImageSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository users;

    @MockitoBean
    private HouseCoverImageCatalog houseCoverImageCatalog;

    // 인증/조회 테스트의 미커밋 사용자를 활동 기록용 REQUIRES_NEW가 기다리지 않게 함.
    // 활동 기록의 트랜잭션/필터 동작은 activity 패키지의 전용 테스트에서 검증함.
    @MockitoBean
    private UserDailyActivityRecorder activityRecorder;

    @Test
    void 토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/houses/cover-images"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }

    @Test
    @Transactional
    void 유효한_토큰이면_목록을_조회한다() throws Exception {
        when(houseCoverImageCatalog.items()).thenReturn(List.of());
        Long userId = users.saveAndFlush(User.signUp()).getId();
        String accessToken = tokenService.issueAccessToken(userId, MemberRole.NORMAL);

        mockMvc.perform(get("/api/v1/houses/cover-images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
