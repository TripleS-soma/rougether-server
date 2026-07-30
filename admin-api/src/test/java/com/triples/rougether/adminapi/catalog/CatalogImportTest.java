package com.triples.rougether.adminapi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.shop.repository.ItemRepository;
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
class CatalogImportTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ItemRepository itemRepository;

    private static final String JSON = """
            {
              "themes": [{"code": "test_theme", "name": "테스트 테마", "active": true}],
              "characters": [{"code": "test_char", "name": "TestChar", "baseAssetKey": "characters/test.png", "sortOrder": 10, "active": true}],
              "items": [{"themeCode": "test_theme", "categoryCode": "furniture", "placementType": "positioned",
                         "surfaceSlotType": null, "characterSlotType": null, "name": "Test Item",
                         "priceAmount": 100, "assetKey": "items/test/item.png", "limited": false, "active": true}]
            }
            """;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 카탈로그_적재_및_멱등() throws Exception {
        // 1차 적재 — 새로 생성
        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themesCreated").value(1))
                .andExpect(jsonPath("$.charactersCreated").value(1))
                .andExpect(jsonPath("$.itemsCreated").value(1));

        // 2차 적재 — 멱등(이미 있어서 0)
        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themesCreated").value(0))
                .andExpect(jsonPath("$.itemsCreated").value(0));

        assertThat(itemRepository.existsByAssetKey("items/test/item.png")).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 캐릭터_악세사리는_가격_입력과_무관하게_뽑기_전용으로_적재한다() throws Exception {
        String accessoryCatalog = """
                {
                  "themes": [{"code": "character_accessories", "name": "캐릭터 꾸미기", "active": true}],
                  "characters": [],
                  "items": [{
                    "themeCode": "character_accessories",
                    "categoryCode": "character_accessory",
                    "placementType": "character",
                    "surfaceSlotType": null,
                    "characterSlotType": "eyewear",
                    "name": "분홍 하트 선글라스",
                    "priceAmount": 999,
                    "assetKey": "items/character-accessories/eyewear/cat-pink-heart-sunglasses/thumbnail.png",
                    "limited": false,
                    "active": true
                  }]
                }
                """;

        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON).content(accessoryCatalog).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsCreated").value(1));

        var accessory = itemRepository.findByAssetKey(
                "items/character-accessories/eyewear/cat-pink-heart-sunglasses/thumbnail.png"
        ).orElseThrow();
        assertThat(accessory.getPurchaseCurrencyType()).isNull();
        assertThat(accessory.getPriceAmount()).isNull();
    }

    @Test
    void 미인증이면_적재_불가() throws Exception {
        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON).content("{}").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
