package com.triples.rougether.userapi.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import com.triples.rougether.userapi.room.web.RoomController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RoomController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private RoomCommandService roomCommandService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser1() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, null));
    }

    @Test
    void 내_방_조회_응답_계약() throws Exception {
        authAsUser1();
        RoomResponse response = new RoomResponse(
                1L,
                5,
                RoomLayoutFormat.SLOT_V1,
                0,
                null,
                List.of(new RoomResponse.RoomSlotResponse("wallpaper", 10L, "items/wp.png", Instant.EPOCH)),
                List.of(),
                new RoomResponse.RoomStreakResponse(3, 7),
                Instant.EPOCH);
        when(roomQueryService.getMyRoom(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/rooms/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomUserId").value(1))
                .andExpect(jsonPath("$.growthLevel").value(5))
                .andExpect(jsonPath("$.layoutFormat").value("SLOT_V1"))
                .andExpect(jsonPath("$.layoutRevision").value(0))
                .andExpect(jsonPath("$.placements").isEmpty())
                .andExpect(jsonPath("$.slots[0].slotType").value("wallpaper"))
                .andExpect(jsonPath("$.slots[0].userItemId").value(10))
                .andExpect(jsonPath("$.slots[0].assetKey").value("items/wp.png"))
                .andExpect(jsonPath("$.streak.currentCount").value(3))
                .andExpect(jsonPath("$.streak.longestCount").value(7));
    }

    @Test
    void 슬롯_배치_저장_응답_계약() throws Exception {
        authAsUser1();
        RoomResponse response = new RoomResponse(
                1L,
                5,
                RoomLayoutFormat.SLOT_V1,
                0,
                null,
                List.of(new RoomResponse.RoomSlotResponse("topLeft", 10L, "items/bed.png", Instant.EPOCH)),
                List.of(),
                new RoomResponse.RoomStreakResponse(0, 0),
                Instant.EPOCH);
        when(roomCommandService.updateSlots(eq(1L), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/rooms/me/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slots\":[{\"slotType\":\"topLeft\",\"userItemId\":10}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].slotType").value("topLeft"))
                .andExpect(jsonPath("$.slots[0].userItemId").value(10));
    }

    @Test
    void 슬롯_목록이_없으면_400() throws Exception {
        authAsUser1();
        mockMvc.perform(put("/api/v1/rooms/me/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 자유배치_저장_응답_계약() throws Exception {
        authAsUser1();
        RoomResponse response = new RoomResponse(
                1L,
                5,
                RoomLayoutFormat.FREE_V1,
                4,
                null,
                List.of(new RoomResponse.RoomSlotResponse("wallpaper", 10L, "items/wp.png", Instant.EPOCH)),
                List.of(new RoomResponse.RoomPlacementResponse(77L, "items/bed.png",
                        new BigDecimal("0.32"), new BigDecimal("0.68"), 3,
                        new BigDecimal("1.1"), 15, false, Instant.EPOCH)),
                new RoomResponse.RoomStreakResponse(0, 0),
                Instant.EPOCH);
        when(roomCommandService.updateLayout(eq(1L), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/rooms/me/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevision\":3,\"surfaceSlots\":[{\"slotType\":\"wallpaper\",\"userItemId\":10}],"
                                + "\"placements\":[{\"userItemId\":77,\"positionX\":0.32,\"positionY\":0.68,"
                                + "\"zIndex\":3,\"scale\":1.1,\"rotationDeg\":15,\"flipped\":false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutFormat").value("FREE_V1"))
                .andExpect(jsonPath("$.layoutRevision").value(4))
                .andExpect(jsonPath("$.placements[0].userItemId").value(77))
                .andExpect(jsonPath("$.placements[0].positionX").value(0.32))
                .andExpect(jsonPath("$.placements[0].zIndex").value(3));
    }

    @Test
    void 자유배치_저장에_baseRevision이_없으면_400() throws Exception {
        authAsUser1();
        mockMvc.perform(put("/api/v1/rooms/me/layout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surfaceSlots\":[],\"placements\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
