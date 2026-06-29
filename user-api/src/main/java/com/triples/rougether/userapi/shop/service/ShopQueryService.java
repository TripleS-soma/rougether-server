package com.triples.rougether.userapi.shop.service;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.shop.dto.ItemListResponse;
import com.triples.rougether.userapi.shop.dto.ItemResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 상점 아이템 목록 조회. 활성 아이템만, owned 는 요청 user 의 user_items 로 판정.
@Service
public class ShopQueryService {

    private final ItemRepository itemRepository;
    private final UserItemRepository userItemRepository;

    public ShopQueryService(ItemRepository itemRepository, UserItemRepository userItemRepository) {
        this.itemRepository = itemRepository;
        this.userItemRepository = userItemRepository;
    }

    @Transactional(readOnly = true)
    public ItemListResponse getItems(Long userId, Long themeId) {
        List<Item> items = (themeId != null)
                ? itemRepository.findActiveWithThemeByThemeId(themeId)
                : itemRepository.findActiveWithTheme();

        Set<Long> ownedItemIds = userItemRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(userItem -> userItem.getItem().getId())
                .collect(Collectors.toSet());

        List<ItemResponse> responses = items.stream()
                .map(item -> ItemResponse.of(item, ownedItemIds.contains(item.getId())))
                .toList();

        return new ItemListResponse(responses);
    }
}
