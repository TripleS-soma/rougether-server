package com.triples.rougether.userapi.character;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.character.dto.CharacterAccessoriesResponse;
import com.triples.rougether.userapi.character.dto.CharacterAnimations;
import com.triples.rougether.userapi.character.dto.EquippedAccessoryResponse;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse.MyCharacterItem;
import com.triples.rougether.userapi.character.service.CharacterAccessoryCommandService;
import com.triples.rougether.userapi.character.service.MyCharacterCommandService;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import com.triples.rougether.userapi.character.web.MyCharacterController;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyCharacterController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyCharacterControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MyCharacterQueryService myCharacterQueryService;
    @MockitoBean private MyCharacterCommandService myCharacterCommandService;
    @MockitoBean private CharacterAccessoryCommandService characterAccessoryCommandService;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    private EquippedAccessoryResponse sunglasses;

    @BeforeEach
    void setUp() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
        sunglasses = new EquippedAccessoryResponse(
                77L, 15L, "검은 뿔테안경", "items/accessories/glasses.png",
                "eyewear", Instant.parse("2026-07-30T09:00:00Z"));
    }

    @Test
    void 내_캐릭터_목록에_캐릭터별_악세사리가_포함된다() throws Exception {
        when(myCharacterQueryService.getMyCharacters(7L)).thenReturn(new MyCharacterListResponse(
                List.of(new MyCharacterItem(
                        12L, 1L, "cat", "고양이", "characters/cat.png",
                        CharacterAnimations.of("cat"), true, List.of(sunglasses),
                        Instant.parse("2026-07-01T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/me/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userCharacterId").value(12))
                .andExpect(jsonPath("$.items[0].accessories[0].userItemId").value(77))
                .andExpect(jsonPath("$.items[0].accessories[0].assetKey")
                        .value("items/accessories/glasses.png"))
                .andExpect(jsonPath("$.items[0].accessories[0].characterSlotType")
                        .value("eyewear"));
    }

    @Test
    void 악세사리_적용과_해제_응답은_갱신된_전체_목록이다() throws Exception {
        CharacterAccessoriesResponse response =
                new CharacterAccessoriesResponse(12L, List.of(sunglasses));
        when(characterAccessoryCommandService.equip(7L, 12L, 77L)).thenReturn(response);
        when(characterAccessoryCommandService.unequip(7L, 12L, "eyewear"))
                .thenReturn(new CharacterAccessoriesResponse(12L, List.of()));

        mockMvc.perform(put("/api/v1/me/characters/12/accessories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userItemId\":77}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCharacterId").value(12))
                .andExpect(jsonPath("$.items[0].userItemId").value(77));

        mockMvc.perform(delete("/api/v1/me/characters/12/accessories/eyewear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCharacterId").value(12))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 적용할_userItemId가_없으면_400이다() throws Exception {
        mockMvc.perform(put("/api/v1/me/characters/12/accessories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
