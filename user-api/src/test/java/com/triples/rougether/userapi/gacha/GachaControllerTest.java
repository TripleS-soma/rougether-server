package com.triples.rougether.userapi.gacha;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaResponse;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.gacha.web.GachaController;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GachaController.class)
@AutoConfigureMockMvc(addFilters = false)
class GachaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GachaService gachaService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void 뽑기_머신_목록_응답_계약() throws Exception {
        when(gachaService.getGachaList()).thenReturn(new GachaListResponse(List.of(
                new GachaResponse(1L, "calm_hanok", "한옥 뽑기", 5L, CurrencyType.COIN, 250, 1, true))));

        mockMvc.perform(get("/api/v1/gacha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].gachaId").value(1))
                .andExpect(jsonPath("$.items[0].code").value("calm_hanok"))
                .andExpect(jsonPath("$.items[0].costAmount").value(250));
    }

    @Test
    void 뽑기_실행_응답_계약() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, null));
        when(gachaService.draw(eq(1L), eq(10L), any())).thenReturn(new GachaDrawResponse(
                List.of(new GachaDrawResponse.DrawResult("ITEM", 100L, null, "가구A", "items/a.png", "일반", false, null)),
                new GachaDrawResponse.WalletSummary(CurrencyType.COIN, 750)));

        mockMvc.perform(post("/api/v1/gacha/10/draw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].rewardType").value("ITEM"))
                .andExpect(jsonPath("$.results[0].rarity").value("일반"))
                .andExpect(jsonPath("$.wallet.balance").value(750));
    }
}
