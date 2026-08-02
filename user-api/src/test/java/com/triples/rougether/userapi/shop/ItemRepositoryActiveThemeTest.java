package com.triples.rougether.userapi.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

// 상점 노출 쿼리 - 활성 테마의 활성 아이템만 노출한다 (spec domains/shop/features.md).
// 테마를 내리면(is_active=false) 그 테마의 활성 아이템도 상점에서 숨겨져야 한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class ItemRepositoryActiveThemeTest {

    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;

    private Theme activeTheme;
    private Theme inactiveTheme;

    @BeforeEach
    void setUp() {
        activeTheme = themeRepository.save(new Theme("shop_active_theme", "활성 테마", null, true));
        inactiveTheme = themeRepository.save(new Theme("shop_inactive_theme", "비활성 테마", null, false));
        itemRepository.save(item(activeTheme, "노출 아이템", "items/shop-filter/visible.png", true));
        itemRepository.save(item(activeTheme, "비활성 아이템", "items/shop-filter/inactive-item.png", false));
        itemRepository.save(item(inactiveTheme, "비활성 테마 아이템", "items/shop-filter/hidden.png", true));
    }

    private Item item(Theme theme, String name, String assetKey, boolean active) {
        return new Item(theme, "furniture", "positioned", null, null,
                name, CurrencyType.DIAMOND, 100, assetKey, false, active);
    }

    @Test
    void 상점_목록은_활성_테마의_활성_아이템만_노출한다() {
        List<Item> items = itemRepository.findActiveWithTheme().stream()
                .filter(found -> found.getAssetKey().startsWith("items/shop-filter/"))
                .toList();

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getAssetKey()).isEqualTo("items/shop-filter/visible.png");
    }

    @Test
    void 테마_필터_조회도_비활성_테마면_빈_목록이다() {
        assertThat(itemRepository.findActiveWithThemeByThemeId(inactiveTheme.getId())).isEmpty();
        assertThat(itemRepository.findActiveWithThemeByThemeId(activeTheme.getId()))
                .extracting(Item::getAssetKey)
                .containsExactly("items/shop-filter/visible.png");
    }
}
