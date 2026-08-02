package com.triples.rougether.userapi.shop;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.shop.dto.ItemListResponse;
import com.triples.rougether.userapi.shop.dto.ItemResponse;
import com.triples.rougether.userapi.shop.service.ShopCommandService;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import com.triples.rougether.userapi.shop.web.ShopController;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShopController.class)
@AutoConfigureMockMvc(addFilters = false)
class ShopControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShopQueryService shopQueryService;

    @MockitoBean
    private ShopCommandService shopCommandService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void 상점_아이템_응답에_defaultScale을_숫자로_직렬화한다() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
        when(shopQueryService.getItems(7L, null)).thenReturn(new ItemListResponse(List.of(
                new ItemResponse(1L, "인벤 의자", "items/inv/chair.png", "positioned",
                        null, null, "midRight", new BigDecimal("1.24"),
                        new BigDecimal("0.35"), new BigDecimal("0.65"), "furniture", "DIAMOND", 100,
                        false, new ItemResponse.ThemeSummary(3L, "inv_test_theme", "인벤토리 테마", null),
                        false),
                new ItemResponse(2L, "공통 위치 의자", "items/inv/default-chair.png", "positioned",
                        null, null, "bottomCenter", BigDecimal.ONE,
                        null, null, "furniture", "DIAMOND", 100,
                        false, new ItemResponse.ThemeSummary(3L, "inv_test_theme", "인벤토리 테마", null),
                        false))));

        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].defaultScale").value(1.24))
                .andExpect(jsonPath("$.items[0].defaultPositionX").value(0.35))
                .andExpect(jsonPath("$.items[0].defaultPositionY").value(0.65))
                .andExpect(jsonPath("$.items[1].defaultPositionX").value(nullValue()))
                .andExpect(jsonPath("$.items[1].defaultPositionY").value(nullValue()));
    }
}
