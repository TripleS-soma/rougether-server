package com.triples.rougether.adminapi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.shop.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

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
    void 캐릭터_악세사리는_등급없이_동일_가중치로_뽑기_풀에_자동_등록한다() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO themes (code, name, is_active)
                VALUES ('character_accessories', '캐릭터 꾸미기', TRUE)
                """);
        Long themeId = jdbcTemplate.queryForObject(
                "SELECT id FROM themes WHERE code = 'character_accessories'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO gacha
                    (code, name, cost_currency_type, cost_amount, draw_count, is_active,
                     created_at, updated_at, theme_id)
                VALUES ('character_accessories_furniture', '캐릭터 꾸미기 가구 뽑기',
                        'COIN', 25, 1, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, themeId);

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

        var entry = jdbcTemplate.queryForMap("""
                SELECT e.reward_type, e.rarity, e.weight, g.code
                FROM gacha_pool_entries e
                JOIN gacha g ON g.id = e.gacha_id
                WHERE e.item_id = ? AND e.is_active = TRUE AND g.is_active = TRUE
                """, accessory.getId());
        assertThat(entry.get("reward_type")).isEqualTo("ITEM");
        assertThat(entry.get("rarity")).isNull();
        assertThat(entry.get("weight")).isEqualTo(1);
        assertThat(entry.get("code")).isEqualTo("character_accessories_accessories");

        Integer gachaCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM gacha
                WHERE theme_id = ? AND cost_currency_type = 'COIN'
                  AND cost_amount = 25 AND draw_count = 1 AND is_active = TRUE
                """, Integer.class, accessory.getTheme().getId());
        assertThat(gachaCount).isEqualTo(2);

        Integer furniturePoolCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM gacha_pool_entries e
                JOIN gacha g ON g.id = e.gacha_id
                WHERE e.item_id = ? AND g.code = 'character_accessories_furniture'
                """, Integer.class, accessory.getId());
        assertThat(furniturePoolCount).isZero();

        itemRepository.flush();
        jdbcTemplate.update("""
                UPDATE items
                SET purchase_currency_type = 'DIAMOND', price_amount = 999
                WHERE id = ?
                """, accessory.getId());
        jdbcTemplate.update("""
                UPDATE gacha_pool_entries
                SET rarity = '희귀', weight = 7
                WHERE item_id = ?
                """, accessory.getId());
        entityManager.clear();

        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON).content(accessoryCatalog).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsCreated").value(0));

        var normalizedAccessory = itemRepository.findById(accessory.getId()).orElseThrow();
        assertThat(normalizedAccessory.getPurchaseCurrencyType()).isNull();
        assertThat(normalizedAccessory.getPriceAmount()).isNull();
        var normalizedEntry = jdbcTemplate.queryForMap("""
                SELECT rarity, weight
                FROM gacha_pool_entries
                WHERE item_id = ? AND is_active = TRUE
                """, accessory.getId());
        assertThat(normalizedEntry.get("rarity")).isNull();
        assertThat(normalizedEntry.get("weight")).isEqualTo(1);

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha_pool_entries WHERE item_id = ? AND is_active = TRUE",
                Integer.class, accessory.getId());
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    void 미인증이면_적재_불가() throws Exception {
        mockMvc.perform(post("/admin/catalog/import")
                        .contentType(MediaType.APPLICATION_JSON).content("{}").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
