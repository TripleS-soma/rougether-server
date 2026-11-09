package com.triples.rougether.userapi.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.shop.dto.ItemListResponse;
import com.triples.rougether.userapi.shop.dto.ItemResponse;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ShopQueryServiceTest {

    @Mock private ItemRepository itemRepository;
    @Mock private UserItemRepository userItemRepository;
    @InjectMocks private ShopQueryService shopQueryService;

    private Item positionedItem(Long id, Theme theme, String defaultSlot) {
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(id);
        when(item.getTheme()).thenReturn(theme);
        when(item.getName()).thenReturn("Test Item " + id);
        when(item.getAssetKey()).thenReturn("items/test/item-" + id + ".png");
        when(item.getPlacementType()).thenReturn("positioned");
        when(item.getDefaultSlot()).thenReturn(defaultSlot);
        when(item.getDefaultScale()).thenReturn(new BigDecimal("1.25"));
        when(item.getDefaultPositionX()).thenReturn(new BigDecimal("0.35"));
        when(item.getDefaultPositionY()).thenReturn(new BigDecimal("0.65"));
        return item;
    }

    @Test
    void 아이템_응답에_defaultSlot이_포함된다() {
        Long userId = 1L;
        Theme theme = mock(Theme.class);
        when(theme.getId()).thenReturn(10L);
        Item withSlot = positionedItem(100L, theme, "bottomCenter");
        Item withoutSlot = positionedItem(101L, theme, null);
        when(itemRepository.findActiveWithTheme()).thenReturn(List.of(withSlot, withoutSlot));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of());

        ItemListResponse response = shopQueryService.getItems(userId, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).defaultSlot()).isEqualTo("bottomCenter");
        assertThat(response.items().get(1).defaultSlot()).isNull();
        assertThat(response.items()).extracting(ItemResponse::defaultScale)
                .allSatisfy(defaultScale -> assertThat(defaultScale).isEqualByComparingTo("1.25"));
        assertThat(response.items()).extracting(ItemResponse::defaultPositionX)
                .allSatisfy(x -> assertThat(x).isEqualByComparingTo("0.35"));
        assertThat(response.items()).extracting(ItemResponse::defaultPositionY)
                .allSatisfy(y -> assertThat(y).isEqualByComparingTo("0.65"));
    }

    @Test
    void 보유한_아이템은_owned_true로_응답한다() {
        Long userId = 1L;
        Theme theme = mock(Theme.class);
        Item owned = positionedItem(100L, theme, "topLeft");
        Item notOwned = positionedItem(101L, theme, null);
        when(itemRepository.findActiveWithTheme()).thenReturn(List.of(owned, notOwned));
        UserItem userItem = mock(UserItem.class);
        when(userItem.getItem()).thenReturn(owned);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of(userItem));

        ItemListResponse response = shopQueryService.getItems(userId, null);

        assertThat(response.items()).extracting(ItemResponse::owned).containsExactly(true, false);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 999L)
    void 전체와_테마별_상점에서_크리스마스_가구_벽지_바닥을_숨긴다(Long themeId) {
        Item regular = catalogItem(100L, "bakery_morning", "furniture", "positioned");
        Item similarCode = catalogItem(101L, "snowy_christmas_202611_extra", "furniture", "positioned");
        List<Item> items = List.of(regular, similarCode,
                catalogItem(102L, "snowy_christmas_202611", "furniture", "positioned"),
                catalogItem(103L, "snowy_christmas_202611", "wallpaper", "surface_slot"),
                catalogItem(104L, "snowy_christmas_202611", "floor", "surface_slot"));
        if (themeId == null) {
            when(itemRepository.findActiveWithTheme()).thenReturn(items);
        } else {
            when(itemRepository.findActiveWithThemeByThemeId(themeId)).thenReturn(items);
        }
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());

        assertThat(shopQueryService.getItems(1L, themeId).items())
                .extracting(ItemResponse::id).containsExactly(100L, 101L);
    }

    @Test
    void 이미_획득한_크리스마스_에셋은_인벤토리에_계속_노출한다() {
        Item christmas = catalogItem(100L, "snowy_christmas_202611", "furniture", "positioned");
        UserItem owned = UserItem.create(User.signUp(), christmas);
        when(userItemRepository.findInventoryByUserId(1L, null)).thenReturn(List.of(owned));

        assertThat(shopQueryService.getMyItems(1L, null).items())
                .extracting(item -> item.itemId()).containsExactly(100L);
    }

    private Item catalogItem(Long id, String themeCode, String category, String placementType) {
        Item item = new Item(new Theme(themeCode, "테마", null, true), category, placementType,
                null, null, "에셋", CurrencyType.DIAMOND, 400, "items/test.png", false, true);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
