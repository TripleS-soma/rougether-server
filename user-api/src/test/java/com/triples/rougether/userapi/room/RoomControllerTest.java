package com.triples.rougether.userapi.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import com.triples.rougether.userapi.room.web.RoomController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    @Test
    void 내_방_조회_응답_계약() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, null));

        RoomResponse response = new RoomResponse(
                1L,
                5,
                List.of(new RoomResponse.RoomSlotResponse("wallpaper", 10L, "items/wp.png", Instant.EPOCH)),
                new RoomResponse.RoomStreakResponse(3, 7),
                Instant.EPOCH);
        when(roomQueryService.getMyRoom(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/rooms/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomUserId").value(1))
                .andExpect(jsonPath("$.growthLevel").value(5))
                .andExpect(jsonPath("$.slots[0].slotType").value("wallpaper"))
                .andExpect(jsonPath("$.slots[0].userItemId").value(10))
                .andExpect(jsonPath("$.slots[0].assetKey").value("items/wp.png"))
                .andExpect(jsonPath("$.streak.currentCount").value(3))
                .andExpect(jsonPath("$.streak.longestCount").value(7));
    }
}
