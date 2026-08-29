package com.triples.rougether.userapi.invite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.invite.dto.InvitePreviewResponse;
import com.triples.rougether.userapi.invite.dto.InviteRedeemResponse;
import com.triples.rougether.userapi.invite.dto.MyInviteCodeResponse;
import com.triples.rougether.userapi.invite.service.InviteService;
import com.triples.rougether.userapi.invite.web.InviteController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InviteController.class)
@AutoConfigureMockMvc(addFilters = false)
class InviteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InviteService inviteService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    @MockitoBean
    private TokenService tokenService;

    @BeforeEach
    void stubCurrentUser() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, null));
    }

    @Test
    void 내_초대코드_조회_응답_계약() throws Exception {
        when(inviteService.getMyCode(1L)).thenReturn(new MyInviteCodeResponse(
                "ABCD2345", "https://invite.rougether.test/i/ABCD2345", 3, 50, 50, 10));

        mockMvc.perform(get("/api/v1/invites/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ABCD2345"))
                .andExpect(jsonPath("$.shareUrl").value("https://invite.rougether.test/i/ABCD2345"))
                .andExpect(jsonPath("$.rewardedCount").value(3))
                .andExpect(jsonPath("$.inviterRewardCoin").value(50))
                .andExpect(jsonPath("$.inviteeRewardCoin").value(50))
                .andExpect(jsonPath("$.maxRewardedCount").value(10));
    }

    @Test
    void 초대코드_미리보기_응답_계약() throws Exception {
        when(inviteService.preview(1L, "ABCD2345"))
                .thenReturn(new InvitePreviewResponse("소마", 50, false));

        mockMvc.perform(get("/api/v1/invites/by-code/ABCD2345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviterNickname").value("소마"))
                .andExpect(jsonPath("$.inviteeRewardCoin").value(50))
                .andExpect(jsonPath("$.alreadyRedeemed").value(false));
    }

    @Test
    void 초대코드_사용_응답_계약() throws Exception {
        when(inviteService.redeem(eq(1L), eq("ABCD2345")))
                .thenReturn(new InviteRedeemResponse(50, 150, true));

        mockMvc.perform(post("/api/v1/invites/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCD2345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardCoin").value(50))
                .andExpect(jsonPath("$.coinBalance").value(150))
                .andExpect(jsonPath("$.inviterRewarded").value(true));
    }

    @Test
    void 초대코드가_비어_있으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/invites/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
