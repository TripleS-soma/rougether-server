package com.triples.rougether.userapi.market;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.web.MarketAssetController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketAssetController.class)
@AutoConfigureMockMvc(addFilters = false)
class MarketAssetControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MarketAssetService marketAssetService;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    @BeforeEach
    void authenticate() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 발행하면_201과_종목_상세를_반환한다() throws Exception {
        when(marketAssetService.issue(7L, 77L, 5)).thenReturn(new MarketAssetResponse(1L, 320L, "고양이 소파",
                "furniture/photo/a.png", "영희", true, 5, 4, MarketAssetStatus.ACTIVE, null, true, List.of(), List.of()));

        mockMvc.perform(post("/api/v1/market/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userItemId\":77,\"totalSupply\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").value(1))
                .andExpect(jsonPath("$.name").value("고양이 소파"))
                .andExpect(jsonPath("$.creatorNickname").value("영희"))
                .andExpect(jsonPath("$.isCreator").value(true))
                .andExpect(jsonPath("$.unissuedQuantity").value(4))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.asks").isEmpty());
    }

    @Test
    void 발행_수량이_범위를_벗어나거나_아이템이_없으면_검증_오류다() throws Exception {
        for (String body : List.of("{\"userItemId\":77,\"totalSupply\":11}",
                "{\"userItemId\":77,\"totalSupply\":0}", "{\"totalSupply\":3}")) {
            mockMvc.perform(post("/api/v1/market/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verify(marketAssetService, never()).issue(anyLong(), anyLong(), anyInt());
    }

    @Test
    void 서비스_오류는_코드와_상태로_변환된다() throws Exception {
        when(marketAssetService.issue(7L, 77L, 3)).thenThrow(new BusinessException(MarketErrorCode.NOT_CREATOR));
        when(marketAssetService.issue(7L, 78L, 3)).thenThrow(new BusinessException(MarketErrorCode.ASSET_ALREADY_LISTED));

        mockMvc.perform(post("/api/v1/market/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userItemId\":77,\"totalSupply\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MARKET_NOT_CREATOR"));
        mockMvc.perform(post("/api/v1/market/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userItemId\":78,\"totalSupply\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MARKET_ASSET_ALREADY_LISTED"));
    }
}
