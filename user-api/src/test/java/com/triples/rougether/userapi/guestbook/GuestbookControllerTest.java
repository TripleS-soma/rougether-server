package com.triples.rougether.userapi.guestbook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateResponse;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse.GuestbookItem;
import com.triples.rougether.userapi.guestbook.service.GuestbookService;
import com.triples.rougether.userapi.guestbook.web.GuestbookController;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GuestbookController.class)
@AutoConfigureMockMvc(addFilters = false)
class GuestbookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuestbookService guestbookService;

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
    void 방명록_목록_응답_계약() throws Exception {
        authAsUser7();
        when(guestbookService.getGuestbooks(7L, 3L, 1L, null, 20)).thenReturn(new GuestbookListResponse(
                List.of(new GuestbookItem(12L, 7L, "진형", "오늘도 루틴 완료!", Instant.parse("2026-07-05T00:00:00Z"))),
                12L, true));

        mockMvc.perform(get("/api/v1/rooms/3/guestbooks").param("houseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].guestbookId").value(12))
                .andExpect(jsonPath("$.items[0].authorNickname").value("진형"))
                .andExpect(jsonPath("$.nextCursor").value(12))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void houseId_없이_조회하면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(get("/api/v1/rooms/3/guestbooks"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void size_상한을_넘으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(get("/api/v1/rooms/3/guestbooks").param("houseId", "1").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 방명록_작성은_201() throws Exception {
        authAsUser7();
        when(guestbookService.write(eq(7L), eq(3L), any())).thenReturn(new GuestbookCreateResponse(
                12L, 3L, 7L, 1L, "방 예쁘다", Instant.parse("2026-07-05T00:00:00Z")));

        mockMvc.perform(post("/api/v1/rooms/3/guestbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"houseId\": 1, \"content\": \"방 예쁘다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guestbookId").value(12))
                .andExpect(jsonPath("$.roomOwnerId").value(3))
                .andExpect(jsonPath("$.authorId").value(7));
    }

    @Test
    void 내용이_비면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(post("/api/v1/rooms/3/guestbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"houseId\": 1, \"content\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 내용이_500자를_넘으면_400() throws Exception {
        authAsUser7();
        String longContent = "가".repeat(501);

        mockMvc.perform(post("/api/v1/rooms/3/guestbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"houseId\": 1, \"content\": \"" + longContent + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 비구성원_작성은_403_에러_계약() throws Exception {
        authAsUser7();
        when(guestbookService.write(eq(7L), eq(3L), any()))
                .thenThrow(new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));

        mockMvc.perform(post("/api/v1/rooms/3/guestbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"houseId\": 1, \"content\": \"몰래 씀\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
    }
}
