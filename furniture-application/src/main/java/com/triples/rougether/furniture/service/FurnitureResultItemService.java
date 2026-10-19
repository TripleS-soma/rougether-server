package com.triples.rougether.furniture.service;

import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 가구 지급과 이미지 교체는 검수 및 생성권 정산과 같은 트랜잭션에서 처리함.
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class FurnitureResultItemService {
    private final ItemRepository items;
    private final ThemeRepository themes;
    private final UserItemRepository inventory;
    private final UserRepository users;

    public Long grant(Long userId, String name, String assetKey) {
        Theme theme = themes.findByCode("photo_furniture").orElseThrow();
        Item item = items.save(new Item(theme, "furniture", "positioned", null, null,
                name, null, null, assetKey, false, false));
        item.updateDefaultSlot("midRight");
        return inventory.save(UserItem.create(users.getReferenceById(userId), item)).getId();
    }

    public boolean replaceImage(Long userId, Long userItemId, String expectedKey, String newKey) {
        UserItem owned = inventory.findOwnedWithItem(userId, userItemId).orElseThrow();
        return items.replaceAssetKeyIfUnchanged(owned.getItem().getId(), expectedKey, newKey) == 1;
    }
}
