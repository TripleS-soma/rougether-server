package com.triples.rougether.userapi.house;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HouseCoverImageSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @MockitoBean
    private HouseCoverImageCatalog houseCoverImageCatalog;

    @Test
    void 토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/houses/cover-images"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
    }

    @Test
    void 유효한_토큰이면_목록을_조회한다() throws Exception {
        when(houseCoverImageCatalog.keys()).thenReturn(List.of());
        String accessToken = tokenService.issueAccessToken(1L, MemberRole.NORMAL);

        mockMvc.perform(get("/api/v1/houses/cover-images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
