package com.triples.rougether.adminapi.itemslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.adminapi.itemslot.service.ItemSlotService;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ItemRarityRollbackTest {

    @Autowired
    ItemSlotService itemSlotService;

    @Autowired
    ThemeRepository themeRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 두번째_풀_갱신이_실패하면_첫번째_풀도_원래_등급으로_롤백된다() {
        Theme theme = themeRepository.save(
                new Theme("rarity_rollback_theme", "등급 롤백 테마", null, true));
        Item item = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "롤백 테스트 가구", CurrencyType.COIN, 100,
                "items/rarity-rollback/furniture.png", false, true));

        Long firstGachaId = insertGacha(theme.getId(), "rarity_rollback_gacha_1");
        Long secondGachaId = insertGacha(theme.getId(), "rarity_rollback_gacha_2");
        insertItemPoolEntry(firstGachaId, item.getId());
        insertItemPoolEntry(secondGachaId, item.getId());

        List<Long> entryIds = jdbcTemplate.queryForList("""
                SELECT id FROM gacha_pool_entries
                WHERE item_id = ? ORDER BY id
                """, Long.class, item.getId());
        Long blockedEntryId = entryIds.getLast();
        String constraintName = "ck_rarity_rollback_entry";
        jdbcTemplate.execute("""
                ALTER TABLE gacha_pool_entries
                ADD CONSTRAINT %s CHECK (id <> %d OR rarity <> '전설')
                """.formatted(constraintName, blockedEntryId));

        try {
            assertThatThrownBy(() -> itemSlotService.updateRarity(item.getId(), "전설"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            List<String> rarities = jdbcTemplate.queryForList("""
                    SELECT rarity FROM gacha_pool_entries
                    WHERE item_id = ? ORDER BY id
                    """, String.class, item.getId());
            assertThat(rarities).containsExactly("일반", "일반");
        } finally {
            jdbcTemplate.execute("ALTER TABLE gacha_pool_entries DROP CONSTRAINT " + constraintName);
        }
    }

    private Long insertGacha(Long themeId, String code) {
        jdbcTemplate.update("""
                INSERT INTO gacha
                    (code, name, cost_currency_type, cost_amount, draw_count,
                     starts_at, ends_at, is_active, created_at, updated_at, theme_id)
                VALUES (?, ?, 'COIN', 100, 1, NULL, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, code, code, themeId);
        return jdbcTemplate.queryForObject("SELECT id FROM gacha WHERE code = ?", Long.class, code);
    }

    private void insertItemPoolEntry(Long gachaId, Long itemId) {
        jdbcTemplate.update("""
                INSERT INTO gacha_pool_entries
                    (gacha_id, reward_type, item_id, character_id, currency_type,
                     reward_amount, rarity, weight, is_active)
                VALUES (?, 'ITEM', ?, NULL, NULL, NULL, '일반', 1, TRUE)
                """, gachaId, itemId);
    }
}
