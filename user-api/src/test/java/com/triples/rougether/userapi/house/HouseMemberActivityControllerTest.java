package com.triples.rougether.userapi.house;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.service.HouseCheerService;
import com.triples.rougether.userapi.house.service.HouseMemberActivityService;
import com.triples.rougether.userapi.house.web.HouseMemberActivityController;
import com.triples.rougether.userapi.room.dto.RoomCobwebCleanResponse;
import com.triples.rougether.userapi.room.service.RoomCobwebService;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HouseMemberActivityController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseMemberActivityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseMemberActivityService houseMemberActivityService;

    @MockitoBean
    private HouseCheerService houseCheerService;

    @MockitoBean
    private RoomCobwebService roomCobwebService;

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
    void 응원_보내기_응답_계약() throws Exception {
        authAsUser7();
        when(houseCheerService.cheer(7L, 1L, 12L, "support")).thenReturn(
                new HouseCheerResponse(31L, 1L, 12L, 8L, "support", LocalDate.parse("2026-07-20")));

        mockMvc.perform(post("/api/v1/houses/1/members/12/cheer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"support\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cheerId").value(31))
                .andExpect(jsonPath("$.houseId").value(1))
                .andExpect(jsonPath("$.targetMembershipId").value(12))
                .andExpect(jsonPath("$.targetUserId").value(8))
                .andExpect(jsonPath("$.type").value("support"))
                .andExpect(jsonPath("$.cheerDate").value("2026-07-20"));
    }

    @Test
    void 집_멤버_방_거미줄_청소_응답_계약() throws Exception {
        authAsUser7();
        when(roomCobwebService.cleanHouseMemberRoom(7L, 1L, 12L)).thenReturn(
                new RoomCobwebCleanResponse(8L, Instant.EPOCH, CurrencyType.COIN, 3, 13));

        mockMvc.perform(post("/api/v1/houses/1/members/12/room/cobweb/clean"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomUserId").value(8))
                .andExpect(jsonPath("$.rewardAmount").value(3))
                .andExpect(jsonPath("$.balance").value(13));
    }

    @Test
    void 응원_타입이_비어있으면_400() throws Exception {
        authAsUser7();
        mockMvc.perform(post("/api/v1/houses/1/members/12/cheer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
