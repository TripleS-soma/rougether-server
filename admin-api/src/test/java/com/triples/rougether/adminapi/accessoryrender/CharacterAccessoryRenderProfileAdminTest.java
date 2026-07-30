package com.triples.rougether.adminapi.accessoryrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterAccessoryRenderProfileRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CharacterAccessoryRenderProfileAdminTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private CharacterAccessoryRenderProfileRepository renderProfileRepository;

    private Item sunglasses;

    @BeforeEach
    void setUp() {
        Theme theme = themeRepository.save(new Theme(
                "accessory_render_theme", "악세사리 렌더", null, true));
        sunglasses = itemRepository.save(new Item(
                theme, "character_accessory", "character", null, "eyewear",
                "선글라스", null, null,
                "items/character-accessories/eyewear/cat-sunglasses/thumbnail.png",
                false, true));
        characterRepository.save(new Character(
                "cat_render", "고양이", "characters/cat.png", 1, true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 프로필을_asset_key와_캐릭터_코드로_멱등_적재하고_조회한다() throws Exception {
        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("0.31000"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0));

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("0.32000"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1));

        var stored = renderProfileRepository.findByItemIdAndCharacterIdAndRenderState(
                sunglasses.getId(),
                characterRepository.findByCode("cat_render").orElseThrow().getId(),
                "default").orElseThrow();
        assertThat(stored.getPositionY()).isEqualByComparingTo(new BigDecimal("0.32000"));

        mockMvc.perform(get("/admin/character-accessory-render-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].itemAssetKey")
                        .value("items/character-accessories/eyewear/cat-sunglasses/thumbnail.png"))
                .andExpect(jsonPath("$.items[0].characterCode").value("cat_render"))
                .andExpect(jsonPath("$.items[0].renderState").value("default"))
                .andExpect(jsonPath("$.items[0].canvasWidth").value(180))
                .andExpect(jsonPath("$.items[0].canvasHeight").value(172))
                .andExpect(jsonPath("$.items[0].assetWidth").value(320))
                .andExpect(jsonPath("$.items[0].assetHeight").value(160))
                .andExpect(jsonPath("$.items[0].positionY").value(0.32))
                .andExpect(jsonPath("$.items[0].widthRatio").value(0.52));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 좌표와_너비_범위를_벗어난_프로필은_거부한다() throws Exception {
        String body = profileBody("1.10000");

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 캔버스와_악세사리_원본_크기는_양수여야_한다() throws Exception {
        String body = profileBody("0.31000")
                .replace("\"canvasWidth\": 180", "\"canvasWidth\": 0");

        mockMvc.perform(post("/admin/character-accessory-render-profiles/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID"));
    }

    private String profileBody(String positionY) {
        return """
                [
                  {
                    "itemAssetKey": "items/character-accessories/eyewear/cat-sunglasses/thumbnail.png",
                    "characterCode": "cat_render",
                    "renderState": "default",
                    "assetKey": "items/character-accessories/eyewear/cat-sunglasses/thumbnail.png",
                    "canvasWidth": 180,
                    "canvasHeight": 172,
                    "assetWidth": 320,
                    "assetHeight": 160,
                    "positionX": 0.50000,
                    "positionY": %s,
                    "widthRatio": 0.5200,
                    "rotationDeg": 0,
                    "zIndex": 20
                  }
                ]
                """.formatted(positionY);
    }
}
