package com.triples.rougether.userapi.market;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.market.dto.MarketCommandAcceptedResponse;
import com.triples.rougether.userapi.market.dto.MarketCommandResponse;
import com.triples.rougether.userapi.market.service.MarketCommandQueryService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import com.triples.rougether.userapi.market.web.MarketOrderController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class MarketOrderControllerTest {

    private static final String REQUEST_ID = "3f2b8c1e-8d4a-4f1b-9c2e-7a6d5e4f3b21";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MarketOrderCommandService commandService;
    @MockitoBean private MarketCommandQueryService queryService;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    @BeforeEach
    void authenticate() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 주문은_202와_접수_ID를_반환한다() throws Exception {
        when(commandService.place(any(), any())).thenReturn(new MarketCommandAcceptedResponse(10L, CommandStatus.PENDING));

        mockMvc.perform(post("/api/v1/market/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 30, 1, null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(10))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void 방향_출처_수량_조합이나_형식이_틀리면_검증_오류다() throws Exception {
        List<String> invalid = List.of(
                order("BUY", 30, 1, "INVENTORY"),   // 매수에 출처
                order("BUY", 30, 2, null),          // 매수 수량 2
                order("SELL", 30, 1, null),         // 매도에 출처 없음
                order("SELL", 30, 2, "INVENTORY"),  // 보유분 매도 수량 2
                order("BUY", 1001, 1, null),        // 가격 상한 초과
                order("BUY", 0, 1, null),           // 가격 하한 미만
                order("SELL", 25, 11, "ISSUANCE"),  // 발행 재고 매도 수량 상한 초과
                "{\"requestId\":\"not-a-uuid\",\"assetId\":1,\"side\":\"BUY\",\"price\":30,\"quantity\":1}");
        for (String body : invalid) {
            mockMvc.perform(post("/api/v1/market/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verify(commandService, never()).place(any(), any());
    }

    @Test
    void 발행_재고_매도는_여러_개를_받는다() throws Exception {
        when(commandService.place(any(), any())).thenReturn(new MarketCommandAcceptedResponse(11L, CommandStatus.PENDING));

        mockMvc.perform(post("/api/v1/market/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(order("SELL", 25, 3, "ISSUANCE")))
                .andExpect(status().isAccepted());
    }

    @Test
    void 취소는_202를_반환하고_요청_ID가_필요하다() throws Exception {
        when(commandService.cancel(7L, 5L, REQUEST_ID)).thenReturn(new MarketCommandAcceptedResponse(12L, CommandStatus.PENDING));

        mockMvc.perform(post("/api/v1/market/orders/5/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + REQUEST_ID + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(12));
        mockMvc.perform(post("/api/v1/market/orders/5/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 접수_결과를_조회한다() throws Exception {
        when(queryService.get(7L, 10L)).thenReturn(new MarketCommandResponse(10L, CommandStatus.REJECTED, "MARKET_SELF_TRADE", null));

        mockMvc.perform(get("/api/v1/market/commands/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectCode").value("MARKET_SELF_TRADE"))
                .andExpect(jsonPath("$.order").doesNotExist());
    }

    private String order(String side, int price, int quantity, String source) {
        return "{\"requestId\":\"" + REQUEST_ID + "\",\"assetId\":1,\"side\":\"" + side + "\",\"price\":" + price
                + ",\"quantity\":" + quantity + (source == null ? "" : ",\"source\":\"" + source + "\"") + "}";
    }
}
