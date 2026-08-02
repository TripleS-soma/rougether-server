package com.triples.rougether.adminapi.itemslot;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.adminapi.itemslot.service.ItemSlotService;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 동시 등록이 테마 머신/엔트리를 중복 생성하지 않는지 검증 — 테마 행 락(SELECT FOR UPDATE) 직렬화의 회귀 방어.
// 락은 커밋 시점에 풀리므로 @Transactional 테스트(단일 트랜잭션)로는 검증 불가 — 실제 커밋 + 수동 정리로 검증한다.
@SpringBootTest
class ItemSlotRegisterConcurrencyTest {

    @Autowired private ItemSlotService itemSlotService;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long themeId;
    private List<Long> itemIds = List.of();

    @AfterEach
    void cleanup() {
        for (Long itemId : itemIds) {
            jdbcTemplate.update("DELETE FROM gacha_pool_entries WHERE item_id = ?", itemId);
        }
        if (themeId != null) {
            jdbcTemplate.update("DELETE FROM gacha WHERE theme_id = ?", themeId);
            itemIds.forEach(itemRepository::deleteById);
            themeRepository.deleteById(themeId);
        }
    }

    @Test
    void 동시에_다른_아이템을_등록해도_테마_머신은_1개만_생긴다() throws Exception {
        Theme theme = themeRepository.save(new Theme("slot_race_theme", "등록 경합 테마", null, true));
        Item first = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "경합 가구 1", CurrencyType.COIN, 100, "items/slot-race/one.png", false, true));
        Item second = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "경합 가구 2", CurrencyType.COIN, 100, "items/slot-race/two.png", false, true));
        themeId = theme.getId();
        itemIds = List.of(first.getId(), second.getId());

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        pool.submit(() -> registerQuietly(start, done, first.getId(), "일반"));
        pool.submit(() -> registerQuietly(start, done, second.getId(), "희귀"));
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 핵심 불변식: 테마 행 락 직렬화로 활성 머신은 정확히 1개, 아이템별 엔트리도 1건씩
        Integer gachaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gacha WHERE theme_id = ?", Integer.class, themeId);
        assertThat(gachaCount).isEqualTo(1);

        for (Long itemId : itemIds) {
            Integer entryCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM gacha_pool_entries WHERE item_id = ?", Integer.class, itemId);
            assertThat(entryCount).isEqualTo(1);
        }
    }

    private void registerQuietly(CountDownLatch start, CountDownLatch done, Long itemId, String rarity) {
        try {
            start.await();
            itemSlotService.updateRarity(itemId, rarity);
        } catch (Exception ignored) {
            // 락 경합 예외가 나더라도 중복 머신/엔트리가 없어야 한다는 본검증은 count 로 수행
        } finally {
            done.countDown();
        }
    }
}
