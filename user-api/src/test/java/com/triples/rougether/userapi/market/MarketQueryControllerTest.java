package com.triples.rougether.userapi.market;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse;
import com.triples.rougether.userapi.market.dto.MyMarketOrderListResponse;
import com.triples.rougether.userapi.market.dto.MyMarketOrderStatus;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketQueryService;
import com.triples.rougether.userapi.market.web.MarketAssetController;
import com.triples.rougether.userapi.market.web.MyMarketOrderController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({MarketAssetController.class, MyMarketOrderController.class})
@AutoConfigureMockMvc(addFilters = false)
class MarketQueryControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MarketAssetService marketAssetService;
    @MockitoBean private MarketQueryService marketQueryService;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    @BeforeEach
    void authenticate() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 목록은_기본_페이지로_조회한다() throws Exception {
        when(marketQueryService.listAssets(7L, 0, 20)).thenReturn(new MarketAssetListResponse(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/market/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 페이지_범위를_벗어나면_검증_오류다() throws Exception {
        mockMvc.perform(get("/api/v1/market/assets").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/market/assets").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/market/assets").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/market/assets/1/trades").param("size", "0")).andExpect(status().isBadRequest());
        verify(marketQueryService, never()).listAssets(anyLong(), anyInt(), anyInt());
        verify(marketQueryService, never()).trades(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 내_주문은_기본으로_대기_중을_보여주고_잘못된_상태값은_거절한다() throws Exception {
        when(marketQueryService.myOrders(7L, MyMarketOrderStatus.OPEN, 0, 20))
                .thenReturn(new MyMarketOrderListResponse(List.of(), 0, 20, 0));
        when(marketQueryService.myOrders(7L, MyMarketOrderStatus.CLOSED, 0, 20))
                .thenReturn(new MyMarketOrderListResponse(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/me/market/orders")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/market/orders").param("status", "CLOSED")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/market/orders").param("status", "FILLED")).andExpect(status().isBadRequest());
        verify(marketQueryService).myOrders(7L, MyMarketOrderStatus.OPEN, 0, 20);
        verify(marketQueryService).myOrders(7L, MyMarketOrderStatus.CLOSED, 0, 20);
    }
}
