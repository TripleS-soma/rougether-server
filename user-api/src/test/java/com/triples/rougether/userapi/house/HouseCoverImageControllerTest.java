package com.triples.rougether.userapi.house;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.house.dto.HouseCoverImageListResponse;
import com.triples.rougether.userapi.house.service.HouseCoverImageQueryService;
import com.triples.rougether.userapi.house.web.HouseCoverImageController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HouseCoverImageController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseCoverImageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseCoverImageQueryService houseCoverImageQueryService;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void 집_커버_이미지_목록_응답_계약() throws Exception {
        when(houseCoverImageQueryService.getCoverImages()).thenReturn(
                new HouseCoverImageListResponse(List.of(
                        new HouseCoverImageListResponse.HouseCoverImage(
                                "forest", "버섯 숲 집", "house/forest.png"),
                        new HouseCoverImageListResponse.HouseCoverImage(
                                "morning", "아침 햇살 집", "house/morning.png"))));

        mockMvc.perform(get("/api/v1/houses/cover-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].code").value("forest"))
                .andExpect(jsonPath("$.items[0].name").value("버섯 숲 집"))
                .andExpect(jsonPath("$.items[0].coverImageKey").value("house/forest.png"))
                .andExpect(jsonPath("$.items[1].code").value("morning"))
                .andExpect(jsonPath("$.items[1].name").value("아침 햇살 집"))
                .andExpect(jsonPath("$.items[1].coverImageKey").value("house/morning.png"));
    }

    @Test
    void 후보가_없으면_빈_목록을_반환한다() throws Exception {
        when(houseCoverImageQueryService.getCoverImages()).thenReturn(
                new HouseCoverImageListResponse(List.of()));

        mockMvc.perform(get("/api/v1/houses/cover-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
