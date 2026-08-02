package com.triples.rougether.userapi.wallet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryDirection;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryListResponse;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryListResponse.WalletHistoryResponse;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import com.triples.rougether.userapi.wallet.web.WalletController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WalletController.class)
@AutoConfigureMockMvc(addFilters = false)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletQueryService walletQueryService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    @MockitoBean
    private TokenService tokenService;

    @BeforeEach
    void stubAuth() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 이력_조회는_페이지네이션_규약_형태로_필터를_전달해_응답한다() throws Exception {
        when(walletQueryService.getHistories(7L, CurrencyType.COIN, WalletHistoryDirection.EARN, 0, 20))
                .thenReturn(new WalletHistoryListResponse(List.of(
                        new WalletHistoryResponse(1L, CurrencyType.COIN, 10,
                                WalletHistoryReason.ROUTINE_COMPLETE, 110,
                                Instant.parse("2026-07-31T02:15:00Z"))),
                        0, 20, 1));

        mockMvc.perform(get("/api/v1/me/wallets/histories")
                        .param("currencyType", "COIN")
                        .param("direction", "EARN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].currencyType").value("COIN"))
                .andExpect(jsonPath("$.items[0].amount").value(10))
                .andExpect(jsonPath("$.items[0].reason").value("ROUTINE_COMPLETE"))
                .andExpect(jsonPath("$.items[0].balanceAfter").value(110))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 필터_없이_호출하면_기본_페이징으로_전체를_조회한다() throws Exception {
        when(walletQueryService.getHistories(7L, null, null, 0, 20))
                .thenReturn(new WalletHistoryListResponse(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/me/wallets/histories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void 잘못된_direction_값은_VALIDATION_FAILED_400() throws Exception {
        mockMvc.perform(get("/api/v1/me/wallets/histories").param("direction", "WRONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 음수_page는_VALIDATION_FAILED_400() throws Exception {
        mockMvc.perform(get("/api/v1/me/wallets/histories").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
