package com.triples.rougether.userapi.house;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.web.HouseController;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HouseController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseCommandService houseCommandService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser7() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 집_생성_응답_계약() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any())).thenReturn(
                new HouseCreateResponse(1L, 7L, "ABCD2345", Instant.parse("2026-07-10T00:00:00Z")));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "아침 루틴 하우스", "description": "같이 아침 루틴",
                                 "maxMembers": 6, "goalIds": [1, 2]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.ownerUserId").value(7))
                .andExpect(jsonPath("$.inviteCode").value("ABCD2345"));
    }

    @Test
    void 없는_목표가_섞이면_400과_에러코드를_내려준다() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any()))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_GOAL_INVALID));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": [1, 99]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOUSE_GOAL_INVALID"));
    }

    @Test
    void goalIds_3개는_정상_생성된다() throws Exception {
        authAsUser7();
        when(houseCommandService.create(eq(7L), any())).thenReturn(
                new HouseCreateResponse(1L, 7L, "ABCD2345", Instant.parse("2026-07-10T00:00:00Z")));

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": [1, 2, 3]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void maxMembers가_상한을_넘으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"maxMembers\": 11, \"goalIds\": [1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 이름이_짧으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"a\", \"goalIds\": [1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void goalIds가_비어있으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void goalIds가_3개를_넘으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"아침 루틴 하우스\", \"goalIds\": [1, 2, 3, 4]}"))
                .andExpect(status().isBadRequest());
    }
}
