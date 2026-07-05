package com.triples.rougether.userapi.house;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.house.dto.HouseMissionClaimResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionContributeResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse.MissionSummary;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.house.web.HouseMissionController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HouseMissionController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseMissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseMissionService houseMissionService;

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

    private HouseMissionResponse missionResponse() {
        return new HouseMissionResponse(3L, "주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 20,
                12L, HouseMissionStatus.ACTIVE, null, null, 3, false,
                Instant.parse("2026-07-05T00:00:00Z"));
    }

    @Test
    void 미션_목록_응답_계약() throws Exception {
        authAsUser7();
        when(houseMissionService.getMissions(7L, 1L)).thenReturn(new HouseMissionListResponse(List.of(
                new MissionSummary(3L, "주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 20, 12L,
                        HouseMissionStatus.ACTIVE, null, null, Instant.parse("2026-07-05T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/houses/1/missions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].missionId").value(3))
                .andExpect(jsonPath("$.items[0].missionType").value("WEEKLY_MEMBER_COUNT"))
                .andExpect(jsonPath("$.items[0].currentValue").value(12))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    void 미션_등록은_201() throws Exception {
        authAsUser7();
        when(houseMissionService.create(eq(7L), eq(1L), any())).thenReturn(missionResponse());

        mockMvc.perform(post("/api/v1/houses/1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "주간 미션", "missionType": "WEEKLY_MEMBER_COUNT", "targetValue": 20}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missionId").value(3))
                .andExpect(jsonPath("$.achieved").value(false));
    }

    @Test
    void 제목이_비면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses/1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "", "missionType": "WEEKLY_MEMBER_COUNT", "targetValue": 20}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 목표치가_범위를_벗어나면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/houses/1/missions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "주간 미션", "missionType": "WEEKLY_MEMBER_COUNT", "targetValue": 1001}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 미션_상세_응답_계약() throws Exception {
        authAsUser7();
        when(houseMissionService.getMission(7L, 1L, 3L)).thenReturn(missionResponse());

        mockMvc.perform(get("/api/v1/houses/1/missions/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missionId").value(3))
                .andExpect(jsonPath("$.myContribution").value(3))
                .andExpect(jsonPath("$.targetValue").value(20));
    }

    @Test
    void 기여_응답_계약() throws Exception {
        authAsUser7();
        when(houseMissionService.contribute(7L, 1L, 3L))
                .thenReturn(new HouseMissionContributeResponse(3L, 4, 13L, false));

        mockMvc.perform(post("/api/v1/houses/1/missions/3/contribute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myContribution").value(4))
                .andExpect(jsonPath("$.currentValue").value(13));
    }

    @Test
    void 같은_날_재기여는_409_에러_계약() throws Exception {
        authAsUser7();
        when(houseMissionService.contribute(7L, 1L, 3L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED));

        mockMvc.perform(post("/api/v1/houses/1/missions/3/contribute"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSE_MISSION_ALREADY_CONTRIBUTED"));
    }

    @Test
    void claim_응답_계약() throws Exception {
        authAsUser7();
        when(houseMissionService.claim(7L, 1L, 3L)).thenReturn(
                new HouseMissionClaimResponse(3L, HouseMissionStatus.COMPLETED, 100, 300, 3));

        mockMvc.perform(post("/api/v1/houses/1/missions/3/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.grantedGrowthPoints").value(100))
                .andExpect(jsonPath("$.houseGrowthPoints").value(300))
                .andExpect(jsonPath("$.houseLevel").value(3));
    }

    @Test
    void 미달성_claim_은_409_에러_계약() throws Exception {
        authAsUser7();
        when(houseMissionService.claim(7L, 1L, 3L))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED));

        mockMvc.perform(post("/api/v1/houses/1/missions/3/claim"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSE_MISSION_NOT_ACHIEVED"));
    }
}
