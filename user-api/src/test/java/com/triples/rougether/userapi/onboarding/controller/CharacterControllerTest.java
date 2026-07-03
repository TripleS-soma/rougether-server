package com.triples.rougether.userapi.onboarding.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.onboarding.dto.CharacterListResponse;
import com.triples.rougether.userapi.onboarding.service.CharacterQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CharacterController.class)
@AutoConfigureMockMvc(addFilters = false)
class CharacterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CharacterQueryService characterQueryService;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void 캐릭터_목록_응답_계약() throws Exception {
        when(characterQueryService.getCharacters()).thenReturn(new CharacterListResponse(
                List.of(new CharacterListResponse.CharacterItem(
                        1L, "cat", "고양이", "characters/cat.png", 0))));

        mockMvc.perform(get("/api/v1/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].code").value("cat"))
                .andExpect(jsonPath("$.items[0].name").value("고양이"))
                .andExpect(jsonPath("$.items[0].baseAssetKey").value("characters/cat.png"))
                .andExpect(jsonPath("$.items[0].sortOrder").value(0));
    }
}
