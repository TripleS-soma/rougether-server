package com.triples.rougether.adminapi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ItemDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ThemeDto;
import com.triples.rougether.adminapi.catalog.error.CatalogImportInvalidException;
import com.triples.rougether.adminapi.catalog.service.CatalogImportService;
import com.triples.rougether.adminapi.itemslot.service.ItemSlotService;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CatalogCategoryRegistrationTest {

    @Autowired CatalogImportService catalogImportService;
    @Autowired ItemSlotService itemSlotService;
    @Autowired ItemRepository itemRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void activateCategoryFixtures() {
        // V65 시드는 비활성이다. 운영 중인 풀을 검증하는 테스트 트랜잭션에서만 활성화한다.
        jdbcTemplate.update("""
                UPDATE gacha SET is_active = TRUE
                WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
                """);
    }

    @Test
    void 다른_테마의_벽지_바닥_가구는_세_전역_머신에_자동_등록된다() {
        var request = new CatalogImportRequest(
                List.of(new ThemeDto("category_ocean", "바다", true),
                        new ThemeDto("category_forest", "숲", true)),
                List.of(),
                List.of(item("category_ocean", "wall", "wallpaper", "surface_slot", "wallpaper", null, true),
                        item("category_forest", "floor", "floor", "surface_slot", "floor", null, true),
                        item("category_ocean", "chair", "furniture", "positioned", null, null, true),
                        item("category_forest", "rug", "floor", "positioned", null, null, true)));

        assertThat(catalogImportService.importCatalog(request).itemsCreated()).isEqualTo(4);
        itemRepository.flush();
        assertThat(entryCodes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "items/category-registration/wall.png", "wallpaper_gacha",
                "items/category-registration/floor.png", "floor_gacha",
                "items/category-registration/chair.png", "furniture_gacha",
                "items/category-registration/rug.png", "furniture_gacha"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT e.rarity FROM gacha_pool_entries e JOIN items i ON i.id = e.item_id
                WHERE i.asset_key LIKE 'items/category-registration/%'
                """, String.class)).containsOnly("일반");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM gacha g JOIN themes t ON t.id = g.theme_id
                WHERE t.code IN ('category_ocean', 'category_forest')
                """, Integer.class)).isZero();
    }

    @Test
    void 배경_잘못된_슬롯_악세사리_비활성_콘텐츠는_자동_등록에서_제외한다() {
        var request = new CatalogImportRequest(
                List.of(new ThemeDto("category_exclusions", "제외 검사", true),
                        new ThemeDto("category_retired", "중지 테마", false)),
                List.of(),
                List.of(item("category_exclusions", "background", "background", "surface_slot", "background", null, true),
                        item("category_exclusions", "positioned-background", "background", "positioned", null, null, true),
                        item("category_exclusions", "invalid-surface", "wallpaper", "surface_slot", "ceiling", null, true),
                        item("category_exclusions", "bad-accessory", "character_accessory", "positioned", null, null, true),
                        item("category_exclusions", "bad-character-slot", "furniture", "positioned", null, "eyewear", true),
                        item("category_exclusions", "inactive", "furniture", "positioned", null, null, false),
                        item("category_retired", "retired-theme", "furniture", "positioned", null, null, true)));

        assertThat(catalogImportService.importCatalog(request).itemsCreated()).isEqualTo(7);
        itemRepository.flush();
        assertThat(entryCodes()).isEmpty();
    }

    @Test
    void 재적재는_설정한_희귀도와_중지된_풀을_보존한다() {
        var request = singleFurniture("preserved");
        catalogImportService.importCatalog(request);
        var item = itemRepository.findByAssetKey("items/category-registration/preserved.png").orElseThrow();
        itemSlotService.updateRarity(item.getId(), "전설");

        assertThat(catalogImportService.importCatalog(request).itemsCreated()).isZero();
        itemRepository.flush();
        assertThat(jdbcTemplate.queryForList(
                "SELECT rarity FROM gacha_pool_entries WHERE item_id = ?", String.class, item.getId()))
                .containsExactly("전설");

        jdbcTemplate.update("UPDATE gacha_pool_entries SET is_active = FALSE WHERE item_id = ?", item.getId());
        entityManager.clear();
        catalogImportService.importCatalog(request);
        itemRepository.flush();
        assertThat(jdbcTemplate.queryForList(
                "SELECT is_active FROM gacha_pool_entries WHERE item_id = ?", Boolean.class, item.getId()))
                .containsExactly(false);
        assertThat(jdbcTemplate.queryForList(
                "SELECT rarity FROM gacha_pool_entries WHERE item_id = ?", String.class, item.getId()))
                .containsExactly("전설");
    }

    @Test
    void 재적재의_active_true가_기존_비활성_아이템을_되살리지_않는다() {
        var request = singleFurniture("inactive-preserved");
        catalogImportService.importCatalog(request);
        var item = itemRepository.findByAssetKey("items/category-registration/inactive-preserved.png").orElseThrow();
        item.deactivate();
        itemRepository.flush();
        catalogImportService.importCatalog(request);
        assertThat(itemRepository.findById(item.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void 기존_카탈로그_아이템은_풀이_없어도_재적재로_새_풀을_만들지_않는다() {
        var request = singleFurniture("legacy-unregistered");
        catalogImportService.importCatalog(request);
        var item = itemRepository.findByAssetKey("items/category-registration/legacy-unregistered.png").orElseThrow();
        itemRepository.flush();
        jdbcTemplate.update("DELETE FROM gacha_pool_entries WHERE item_id = ?", item.getId());
        entityManager.clear();

        assertThat(catalogImportService.importCatalog(request).itemsCreated()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha_pool_entries WHERE item_id = ?",
                Integer.class, item.getId())).isZero();
    }

    @Test
    void 카테고리_머신이_중지되면_운영_상태를_안내하고_새_머신을_만들지_않는다() {
        jdbcTemplate.update("UPDATE gacha SET is_active = FALSE WHERE code = 'furniture_gacha'");
        assertThatThrownBy(() -> catalogImportService.importCatalog(singleFurniture("stopped")))
                .isInstanceOf(CatalogImportInvalidException.class)
                .hasMessageContaining("운영 상태").hasMessageContaining("furniture_gacha");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha WHERE code = 'furniture_gacha'", Integer.class)).isEqualTo(1);
    }

    private CatalogImportRequest singleFurniture(String key) {
        return new CatalogImportRequest(List.of(new ThemeDto("category_single", "단일 테스트", true)), List.of(),
                List.of(item("category_single", key, "furniture", "positioned", null, null, true)));
    }

    private ItemDto item(String theme, String key, String category, String placement,
                         String surface, String characterSlot, boolean active) {
        return new ItemDto(theme, category, placement, surface, characterSlot, key, 100,
                "items/category-registration/" + key + ".png", false, active);
    }

    private Map<String, String> entryCodes() {
        return jdbcTemplate.query("""
                SELECT i.asset_key, g.code FROM gacha_pool_entries e
                JOIN items i ON i.id = e.item_id JOIN gacha g ON g.id = e.gacha_id
                WHERE i.asset_key LIKE 'items/category-registration/%'
                """, rows -> {
            Map<String, String> result = new java.util.HashMap<>();
            while (rows.next()) {
                assertThat(result.put(rows.getString(1), rows.getString(2))).isNull();
            }
            return result;
        });
    }
}
