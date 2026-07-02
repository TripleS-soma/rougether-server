package com.triples.rougether.userapi.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.shop.dto.ItemListResponse;
import com.triples.rougether.userapi.shop.dto.ItemResponse;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        return item;
    }

    @Test
    void 아이템_응답에_defaultSlot이_포함된다() {
        Long userId = 1L;
        Theme theme = mock(Theme.class);
        when(theme.getId()).thenReturn(10L);
        Item withSlot = positionedItem(100L, theme, "midCenter");
        Item withoutSlot = positionedItem(101L, theme, null);
        when(itemRepository.findActiveWithTheme()).thenReturn(List.of(withSlot, withoutSlot));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of());

        ItemListResponse response = shopQueryService.getItems(userId, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).defaultSlot()).isEqualTo("midCenter");
        assertThat(response.items().get(1).defaultSlot()).isNull();
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
}
