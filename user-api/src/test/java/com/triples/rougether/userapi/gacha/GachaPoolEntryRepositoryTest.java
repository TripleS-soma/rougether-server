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
    void 보상_미리보기와_추첨_풀은_회수된_캐릭터를_제외한다() {
        Theme theme = themeRepository.save(new Theme("reward_character", "캐릭터 보상", null, true));
        Gacha gacha = gachaRepository.save(
                new Gacha("reward_character", "캐릭터 뽑기", CurrencyType.COIN, 500, 1, null, true));
        Item placeholder = itemRepository.save(
                item(theme, "캐릭터 엔트리 생성용", "items/reward-character/placeholder.png"));
        Character activeCharacter = characterRepository.save(
                new Character("reward_active_cat", "활성 고양이",
                        "characters/reward-character/active.png", 1, true));
        Character retiredCharacter = characterRepository.save(
                new Character("reward_retired_cat", "회수 고양이",
                        "characters/reward-character/retired.png", 2, false));

        GachaPoolEntry activeEntry = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, placeholder, GachaRarity.NORMAL));
        GachaPoolEntry retiredEntry = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, placeholder, GachaRarity.NORMAL));
        poolRepository.flush();
        changeToCharacterEntry(activeEntry.getId(), activeCharacter.getId());
        changeToCharacterEntry(retiredEntry.getId(), retiredCharacter.getId());
        entityManager.clear();

        assertThat(poolRepository.findActiveRewardsByGachaId(gacha.getId()))
                .extracting(entry -> entry.getCharacter().getId())
                .containsExactly(activeCharacter.getId());
        assertThat(poolRepository.findByGachaIdAndActiveIsTrue(gacha.getId()))
                .extracting(entry -> entry.getCharacter().getId())
                .containsExactly(activeCharacter.getId());
    }

    @Test
    void 보상_미리보기와_추첨_풀은_비활성_아이템만_제외하고_같은_풀의_캐릭터_엔트리는_유지한다() {
        Theme theme = themeRepository.save(new Theme("reward_inactive", "비활성 아이템 테마", null, true));
        Theme retiredTheme = themeRepository.save(new Theme("reward_retired_theme", "내려간 테마", null, false));
        Gacha gacha = gachaRepository.save(
                new Gacha("reward_inactive", "비활성 아이템 뽑기", CurrencyType.COIN, 25, 1, theme, true));
        Item activeItem = itemRepository.save(item(theme, "판매 중", "items/reward-inactive/active.png"));
        Item inactiveItem = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "내려간 아이템", null, null, "items/reward-inactive/inactive.png", false, false));
        Item retiredThemeItem = itemRepository.save(
                item(retiredTheme, "테마째 내려간 아이템", "items/reward-inactive/retired-theme.png"));
        Character character = characterRepository.save(new Character(
                "reward_mixed_cat", "혼합 풀 고양이", "characters/reward-mixed/cat.png", 3, true));

        GachaPoolEntry activeEntry = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, activeItem, GachaRarity.NORMAL));
        poolRepository.save(GachaPoolEntry.itemEntry(gacha, inactiveItem, GachaRarity.NORMAL));
        poolRepository.save(GachaPoolEntry.itemEntry(gacha, retiredThemeItem, GachaRarity.NORMAL));
        GachaPoolEntry characterEntry = poolRepository.save(
                GachaPoolEntry.itemEntry(gacha, activeItem, GachaRarity.NORMAL));
        poolRepository.flush();
        // 아이템 활성 필터가 item 이 null 인 CHARACTER 엔트리를 떨어뜨리지 않는지 혼합 풀로 검증
        changeToCharacterEntry(characterEntry.getId(), character.getId());
        entityManager.clear();

        assertThat(poolRepository.findActiveRewardsByGachaId(gacha.getId()))
                .extracting(GachaPoolEntry::getId)
                .containsExactly(activeEntry.getId(), characterEntry.getId());
        assertThat(poolRepository.findByGachaIdAndActiveIsTrue(gacha.getId()))
                .extracting(GachaPoolEntry::getId)
                .containsExactlyInAnyOrder(activeEntry.getId(), characterEntry.getId());
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

    private void changeToCharacterEntry(Long entryId, Long characterId) {
        jdbcTemplate.update("""
                update gacha_pool_entries
                set reward_type = 'CHARACTER', item_id = null, character_id = ?
                where id = ?
                """, characterId, entryId);
    }
}
