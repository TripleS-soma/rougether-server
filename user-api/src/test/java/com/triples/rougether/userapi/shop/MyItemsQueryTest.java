package com.triples.rougether.userapi.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.shop.dto.MyItemListResponse;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 인벤토리 쿼리 의미 검증 - 보유만·최근 획득 순·삭제 제외·categoryCode 필터·필드 매핑을 실제 DB(H2)로 확인.
@SpringBootTest
@Transactional
class MyItemsQueryTest {

    @Autowired private ShopQueryService shopQueryService;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User me;
    private UserItem oldChair;
    private UserItem newWallpaper;
    private UserItem deletedRug;
    private Item notOwnedItem;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.signUp("my-items-test@rougether.dev"));
        Theme theme = themeRepository.save(new Theme("inv_test_theme", "인벤토리 테마", "themes/inv.png", true));

        Item chair = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "인벤 의자", CurrencyType.DIAMOND, 100, "items/inv/chair.png", false, true));
        chair.updateDefaultSlot("midRight");
        Item wallpaper = itemRepository.save(new Item(theme, "wallpaper", "surface_slot", "wallpaper", null,
                "인벤 벽지", CurrencyType.DIAMOND, 100, "items/inv/wall.png", false, true));
        Item rug = itemRepository.save(new Item(theme, "rug", "positioned", null, null,
                "인벤 러그", CurrencyType.DIAMOND, 100, "items/inv/rug.png", false, true));
        notOwnedItem = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "미보유 가구", CurrencyType.DIAMOND, 100, "items/inv/none.png", false, true));

        oldChair = userItemRepository.save(UserItem.create(me, chair));
        newWallpaper = userItemRepository.save(UserItem.create(me, wallpaper));
        deletedRug = userItemRepository.save(UserItem.create(me, rug));
        // 획득 시각을 명시적으로 벌려 정렬을 결정적으로 만든다.
        jdbcTemplate.update("UPDATE user_items SET acquired_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")), oldChair.getId());
        jdbcTemplate.update("UPDATE user_items SET acquired_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-07-05T00:00:00Z")), newWallpaper.getId());
        jdbcTemplate.update("UPDATE user_items SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedRug.getId());
    }

    @Test
    void 보유_아이템만_최근_획득_순으로_내려준다() {
        MyItemListResponse response = shopQueryService.getMyItems(me.getId(), null);

        assertThat(response.items()).extracting(MyItemListResponse.MyItemSummary::userItemId)
                .containsExactly(newWallpaper.getId(), oldChair.getId()); // 최근 먼저, 삭제된 러그 제외
        assertThat(response.items()).extracting(MyItemListResponse.MyItemSummary::itemId)
                .doesNotContain(notOwnedItem.getId());
    }

    @Test
    void categoryCode로_필터링한다() {
        MyItemListResponse response = shopQueryService.getMyItems(me.getId(), "furniture");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).userItemId()).isEqualTo(oldChair.getId());
    }

    @Test
    void 배치에_필요한_필드가_매핑된다() {
        MyItemListResponse response = shopQueryService.getMyItems(me.getId(), "furniture");

        MyItemListResponse.MyItemSummary chair = response.items().get(0);
        assertThat(chair.name()).isEqualTo("인벤 의자");
        assertThat(chair.assetKey()).isEqualTo("items/inv/chair.png");
        assertThat(chair.placementType()).isEqualTo("positioned");
        assertThat(chair.defaultSlot()).isEqualTo("midRight");
        assertThat(chair.theme().code()).isEqualTo("inv_test_theme");
        assertThat(chair.acquiredAt()).isNotNull();
    }

    @Test
    void 빈_문자열_categoryCode는_필터_없이_전체를_반환한다() {
        MyItemListResponse response = shopQueryService.getMyItems(me.getId(), "");

        assertThat(response.items()).hasSize(2); // blank -> null 정규화, 필터 미적용
    }

    @Test
    void 아무것도_보유하지_않았으면_빈_목록이다() {
        User nobody = userRepository.save(User.signUp("my-items-nobody@rougether.dev"));

        assertThat(shopQueryService.getMyItems(nobody.getId(), null).items()).isEmpty();
    }
}
