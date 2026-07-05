package com.triples.rougether.userapi.shop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.shop.dto.ItemResponse;
import com.triples.rougether.userapi.shop.dto.MyItemListResponse;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import com.triples.rougether.userapi.shop.web.MyItemController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyItemController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShopQueryService shopQueryService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    @Test
    void 인벤토리_응답_계약() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
        when(shopQueryService.getMyItems(7L, "furniture")).thenReturn(new MyItemListResponse(List.of(
                new MyItemListResponse.MyItemSummary(77L, 1L, "인벤 의자", "items/inv/chair.png",
                        "furniture", "positioned", null, null, "midRight",
                        new ItemResponse.ThemeSummary(3L, "inv_test_theme", "인벤토리 테마", null),
                        Instant.parse("2026-07-05T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/me/items").param("categoryCode", "furniture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userItemId").value(77))
                .andExpect(jsonPath("$.items[0].itemId").value(1))
                .andExpect(jsonPath("$.items[0].name").value("인벤 의자"))
                .andExpect(jsonPath("$.items[0].placementType").value("positioned"))
                .andExpect(jsonPath("$.items[0].defaultSlot").value("midRight"))
                .andExpect(jsonPath("$.items[0].theme.code").value("inv_test_theme"));
    }
}
