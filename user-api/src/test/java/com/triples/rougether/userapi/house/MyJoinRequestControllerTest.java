package com.triples.rougether.userapi.house;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import com.triples.rougether.userapi.house.dto.MyJoinRequestListResponse;
import com.triples.rougether.userapi.house.dto.MyJoinRequestListResponse.MyJoinRequestSummary;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import com.triples.rougether.userapi.house.web.MyJoinRequestController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyJoinRequestController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyJoinRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseQueryService houseQueryService;

    @MockitoBean
    private HouseJoinService houseJoinService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser7() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 내_입주_신청_목록_응답_계약() throws Exception {
        authAsUser7();
        when(houseQueryService.getMyJoinRequests(7L, HouseJoinRequestStatus.PENDING))
                .thenReturn(new MyJoinRequestListResponse(List.of(new MyJoinRequestSummary(
                        21L, 1L, "아침 루틴 하우스", "house/cover.png",
                        List.of(new GoalSummary(1L, "morning_routine", "아침 루틴")),
                        HouseJoinRequestStatus.PENDING, Instant.parse("2026-08-01T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/me/join-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestId").value(21))
                .andExpect(jsonPath("$.items[0].houseId").value(1))
                .andExpect(jsonPath("$.items[0].houseName").value("아침 루틴 하우스"))
                .andExpect(jsonPath("$.items[0].coverImageKey").value("house/cover.png"))
                .andExpect(jsonPath("$.items[0].goals[0].code").value("morning_routine"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
    }

    @Test
    void status_파라미터로_다른_상태를_조회한다() throws Exception {
        authAsUser7();
        when(houseQueryService.getMyJoinRequests(7L, HouseJoinRequestStatus.REJECTED))
                .thenReturn(new MyJoinRequestListResponse(List.of()));

        mockMvc.perform(get("/api/v1/me/join-requests").param("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        verify(houseQueryService).getMyJoinRequests(7L, HouseJoinRequestStatus.REJECTED);
    }

    @Test
    void 철회는_204_를_반환한다() throws Exception {
        authAsUser7();

        mockMvc.perform(delete("/api/v1/me/join-requests/21"))
                .andExpect(status().isNoContent());

        verify(houseJoinService).withdrawRequest(7L, 21L);
    }

    @Test
    void 존재하지_않는_신청_철회는_404() throws Exception {
        authAsUser7();
        doThrow(new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_FOUND))
                .when(houseJoinService).withdrawRequest(7L, 999L);

        mockMvc.perform(delete("/api/v1/me/join-requests/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOUSE_JOIN_REQUEST_NOT_FOUND"));
    }

    @Test
    void 이미_처리된_신청_철회는_409() throws Exception {
        authAsUser7();
        doThrow(new BusinessException(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_PENDING))
                .when(houseJoinService).withdrawRequest(7L, 22L);

        mockMvc.perform(delete("/api/v1/me/join-requests/22"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOUSE_JOIN_REQUEST_NOT_PENDING"));
    }
}
