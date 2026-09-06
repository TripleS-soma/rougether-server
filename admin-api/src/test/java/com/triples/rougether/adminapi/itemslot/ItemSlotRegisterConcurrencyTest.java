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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 서로 다른 테마의 동시 등록도 전역 카테고리 머신 락으로 직렬화되는지 검증한다.
// 락은 커밋 시점에 풀리므로 @Transactional 테스트(단일 트랜잭션)로는 검증 불가 — 실제 커밋 + 수동 정리로 검증한다.
@SpringBootTest
class ItemSlotRegisterConcurrencyTest {

    @Autowired private ItemSlotService itemSlotService;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private List<Long> themeIds = List.of();
    private List<Long> itemIds = List.of();

    @AfterEach
    void cleanup() {
        for (Long itemId : itemIds) {
            jdbcTemplate.update("DELETE FROM gacha_pool_entries WHERE item_id = ?", itemId);
        }
        itemIds.forEach(itemRepository::deleteById);
        for (Long themeId : themeIds) {
            themeRepository.deleteById(themeId);
        }
    }

    @Test
    void 다른_테마의_동시_등록도_공통_가구_머신에_성공하고_중복이_없다() throws Exception {
        Theme theme = themeRepository.save(new Theme("slot_race_theme", "등록 경합 테마", null, true));
        Theme anotherTheme = themeRepository.save(new Theme("slot_race_theme_other", "다른 경합 테마", null, true));
        Item first = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "경합 가구 1", CurrencyType.COIN, 100, "items/slot-race/one.png", false, true));
        Item second = itemRepository.save(new Item(
                anotherTheme, "furniture", "positioned", null, null,
                "경합 가구 2", CurrencyType.COIN, 100, "items/slot-race/two.png", false, true));
        themeIds = List.of(theme.getId(), anotherTheme.getId());
        itemIds = List.of(first.getId(), second.getId());
        registerConcurrently(first.getId(), second.getId());
        assertSingleCategoryEntries();
    }

    @Test
    void 같은_아이템_동시_등록은_두_요청_모두_성공하고_엔트리는_하나이다() throws Exception {
        Theme theme = themeRepository.save(new Theme("slot_same_item_race", "동일 아이템 경합", null, true));
        Item item = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "동시 등록 가구", CurrencyType.COIN, 100, "items/slot-race/same.png", false, true));
        themeIds = List.of(theme.getId());
        itemIds = List.of(item.getId());
        registerConcurrently(item.getId(), item.getId());
        assertSingleCategoryEntries();
    }

    private void assertSingleCategoryEntries() {
        for (Long themeId : themeIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM gacha WHERE theme_id = ?", Integer.class, themeId)).isZero();
        }
        for (Long itemId : itemIds) {
            assertThat(jdbcTemplate.queryForList("""
                    SELECT g.code FROM gacha_pool_entries e JOIN gacha g ON g.id = e.gacha_id
                    WHERE e.item_id = ?
                    """, String.class, itemId)).containsExactly("furniture_gacha");
        }
    }

    private void registerConcurrently(Long firstItemId, Long secondItemId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                start.await();
                return itemSlotService.updateRarity(firstItemId, "일반");
            });
            Future<?> second = pool.submit(() -> {
                start.await();
                return itemSlotService.updateRarity(secondItemId, "희귀");
            });
            start.countDown();
            // worker 예외를 삼키지 않는다. 중복 방지와 두 요청의 성공을 모두 검증한다.
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
