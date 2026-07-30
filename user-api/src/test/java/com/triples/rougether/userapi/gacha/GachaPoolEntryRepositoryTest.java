package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
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
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserItemRepository userItemRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;
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

    @Test
    void 보유_여부용_projection은_삭제되지_않은_보상_ID만_조회한다() {
        User user = userRepository.save(User.signUp("gacha-reward-owner@rougether.dev"));
        Theme theme = themeRepository.save(new Theme("reward_owned", "보유 보상", null, true));
        Item ownedItem = itemRepository.save(item(theme, "보유 아이템", "items/reward-owned/item.png"));
        Item deletedItem = itemRepository.save(item(theme, "삭제 아이템", "items/reward-owned/deleted.png"));
        Character ownedCharacter = characterRepository.save(
                new Character("reward_owned_cat", "보유 고양이", "characters/reward-owned/cat.png", 1, true));
        Character deletedCharacter = characterRepository.save(
                new Character("reward_deleted_cat", "삭제 고양이", "characters/reward-owned/deleted.png", 2, true));

        userItemRepository.save(UserItem.create(user, ownedItem));
        UserItem deletedUserItem = userItemRepository.save(UserItem.create(user, deletedItem));
        userCharacterRepository.save(UserCharacter.create(user, ownedCharacter));
        UserCharacter deletedUserCharacter =
                userCharacterRepository.save(UserCharacter.create(user, deletedCharacter));
        userItemRepository.flush();
        userCharacterRepository.flush();
        jdbcTemplate.update("update user_items set deleted_at = current_timestamp where id = ?",
                deletedUserItem.getId());
        jdbcTemplate.update("update user_characters set deleted_at = current_timestamp where id = ?",
                deletedUserCharacter.getId());
        entityManager.clear();

        assertThat(userItemRepository.findOwnedItemIdsByUserId(user.getId()))
                .containsExactly(ownedItem.getId());
        assertThat(userCharacterRepository.findOwnedCharacterIdsByUserId(user.getId()))
                .containsExactly(ownedCharacter.getId());
    }

    private Item item(Theme theme, String name, String assetKey) {
        return new Item(theme, "furniture", "positioned", null, null,
                name, null, null, assetKey, false, true);
    }
}
