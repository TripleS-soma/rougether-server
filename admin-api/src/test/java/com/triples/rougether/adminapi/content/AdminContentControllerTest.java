package com.triples.rougether.adminapi.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AdminContentControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ThemeRepository themeRepository;
    @Autowired
    ItemRepository itemRepository;
    @Autowired
    CharacterRepository characterRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 어드민_메인_화면이_콘텐츠_폼과_목록을_렌더링한다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 업로드한_asset_key를_테마_아이템_캐릭터_데이터에_저장한다() throws Exception {
        MvcResult themeResult = mockMvc.perform(post("/admin/content/themes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "test_theme_content",
                                  "name": "테스트 테마",
                                  "coverImageKey": "themes/theme-key.png",
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverImageKey").value("themes/theme-key.png"))
                .andReturn();
        Number themeId = JsonPath.read(themeResult.getResponse().getContentAsString(), "$.id");

        MvcResult itemResult = mockMvc.perform(post("/admin/content/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "themeId": %d,
                                  "categoryCode": "bed",
                                  "placementType": "SURFACE",
                                  "surfaceSlotType": "BED",
                                  "name": "구름 침대",
                                  "purchaseCurrencyType": "DIAMOND",
                                  "priceAmount": 120,
                                  "assetKey": "items/bed-key.png",
                                  "limited": false,
                                  "active": true
                                }
                                """.formatted(themeId.longValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetKey").value("items/bed-key.png"))
                .andReturn();
        Number itemId = JsonPath.read(itemResult.getResponse().getContentAsString(), "$.id");

        MvcResult characterResult = mockMvc.perform(post("/admin/content/characters")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "test_bear_content",
                                  "name": "테스트 곰돌이",
                                  "baseAssetKey": "characters/bear-key.png",
                                  "sortOrder": 3,
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseAssetKey").value("characters/bear-key.png"))
                .andReturn();
        Number characterId = JsonPath.read(characterResult.getResponse().getContentAsString(), "$.id");

        assertThat(themeRepository.findById(themeId.longValue()).orElseThrow().getCoverImageKey())
                .isEqualTo("themes/theme-key.png");
        assertThat(itemRepository.findById(itemId.longValue()).orElseThrow().getAssetKey())
                .isEqualTo("items/bed-key.png");
        assertThat(characterRepository.findById(characterId.longValue()).orElseThrow().getBaseAssetKey())
                .isEqualTo("characters/bear-key.png");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 기존_테마의_coverImageKey를_교체할_수_있다() throws Exception {
        MvcResult themeResult = mockMvc.perform(post("/admin/content/themes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "replace_theme_content",
                                  "name": "교체 전",
                                  "coverImageKey": "themes/before.png",
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Number themeId = JsonPath.read(themeResult.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/admin/content/themes/{themeId}", themeId.longValue())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "replace_theme_content",
                                  "name": "교체 후",
                                  "coverImageKey": "themes/after.png",
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("교체 후"))
                .andExpect(jsonPath("$.coverImageKey").value("themes/after.png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void surface_아이템은_surfaceSlotType이_필요하다() throws Exception {
        MvcResult themeResult = mockMvc.perform(post("/admin/content/themes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "invalid_item_theme_content",
                                  "name": "검증 테마",
                                  "coverImageKey": "themes/invalid.png",
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Number themeId = JsonPath.read(themeResult.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/admin/content/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "themeId": %d,
                                  "categoryCode": "bed",
                                  "placementType": "SURFACE",
                                  "name": "슬롯 없는 침대",
                                  "assetKey": "items/no-slot.png",
                                  "active": true
                                }
                                """.formatted(themeId.longValue())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 아이템_가격은_currency와_amount를_함께_입력해야_한다() throws Exception {
        MvcResult themeResult = mockMvc.perform(post("/admin/content/themes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "price_pair_theme_content",
                                  "name": "가격 검증 테마",
                                  "coverImageKey": "themes/price.png",
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Number themeId = JsonPath.read(themeResult.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/admin/content/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "themeId": %d,
                                  "categoryCode": "bed",
                                  "placementType": "SURFACE",
                                  "surfaceSlotType": "BED",
                                  "name": "가격 빠진 침대",
                                  "purchaseCurrencyType": "DIAMOND",
                                  "assetKey": "items/no-price.png",
                                  "active": true
                                }
                                """.formatted(themeId.longValue())))
                .andExpect(status().isBadRequest());
    }
}
