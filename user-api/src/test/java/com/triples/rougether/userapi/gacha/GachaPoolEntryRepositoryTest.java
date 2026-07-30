package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class GachaPoolEntryRepositoryTest {

    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private GachaRepository gachaRepository;
    @Autowired private GachaPoolEntryRepository poolRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 보상_미리보기는_활성_엔트리만_ID순으로_보상과_함께_조회한다() {
        Theme theme = themeRepository.save(new Theme("reward_preview", "보상 미리보기", null, true));
        Gacha gacha = gachaRepository.save(
                new Gacha("reward_preview", "보상 미리보기 뽑기", CurrencyType.COIN, 25, 1, theme, true));
        Item firstItem = itemRepository.save(item(theme, "첫 번째", "items/reward-preview/first.png"));
        Item secondItem = itemRepository.save(item(theme, "두 번째", "items/reward-preview/second.png"));
        Item hiddenItem = itemRepository.save(item(theme, "비활성", "items/reward-preview/hidden.png"));

        GachaPoolEntry first = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, firstItem, GachaRarity.NORMAL));
        GachaPoolEntry second = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, secondItem, GachaRarity.RARE));
        GachaPoolEntry hidden = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, hiddenItem, GachaRarity.LEGENDARY));
        poolRepository.flush();
        jdbcTemplate.update("update gacha_pool_entries set is_active = false where id = ?", hidden.getId());
        entityManager.clear();

        List<GachaPoolEntry> rewards = poolRepository.findActiveRewardsByGachaId(gacha.getId());
        entityManager.clear();

        assertThat(rewards).extracting(GachaPoolEntry::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(rewards).extracting(entry -> entry.getItem().getName())
                .containsExactly("첫 번째", "두 번째");
    }

    private Item item(Theme theme, String name, String assetKey) {
        return new Item(theme, "furniture", "positioned", null, null,
                name, null, null, assetKey, false, true);
    }
}
