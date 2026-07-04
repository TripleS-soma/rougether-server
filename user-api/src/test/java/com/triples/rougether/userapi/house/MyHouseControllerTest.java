package com.triples.rougether.userapi.house;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import com.triples.rougether.userapi.house.web.MyHouseController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHouseController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyHouseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseQueryService houseQueryService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    @Test
    void 내_집_목록_응답_계약() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
        when(houseQueryService.getMyHouses(7L)).thenReturn(new MyHouseListResponse(List.of(
                new MyHouseListResponse.MyHouseSummary(1L, "아침 루틴 하우스", "house/cover.png",
                        0, 3, 4, HouseMemberRole.OWNER, Instant.parse("2026-07-03T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/me/houses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].houseId").value(1))
                .andExpect(jsonPath("$.items[0].name").value("아침 루틴 하우스"))
                .andExpect(jsonPath("$.items[0].myRole").value("OWNER"))
                .andExpect(jsonPath("$.items[0].currentMemberCount").value(3))
                .andExpect(jsonPath("$.items[0].level").value(0));
    }
}
