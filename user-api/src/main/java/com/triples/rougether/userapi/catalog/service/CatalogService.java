package com.triples.rougether.userapi.catalog.service;

import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.domain.shop.entity.PlacementType;
import com.triples.rougether.userapi.catalog.dto.CharacterListResponse;
import com.triples.rougether.userapi.catalog.dto.CharacterResponse;
import com.triples.rougether.userapi.catalog.dto.ItemListResponse;
import com.triples.rougether.userapi.catalog.dto.ItemResponse;
import com.triples.rougether.userapi.catalog.dto.ThemeListResponse;
import com.triples.rougether.userapi.catalog.dto.ThemeResponse;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ThemeRepository themeRepository;
    private final ItemRepository itemRepository;
    private final UserItemRepository userItemRepository;
    private final CharacterRepository characterRepository;

    public CatalogService(
            ThemeRepository themeRepository,
            ItemRepository itemRepository,
            UserItemRepository userItemRepository,
            CharacterRepository characterRepository) {
        this.themeRepository = themeRepository;
        this.itemRepository = itemRepository;
        this.userItemRepository = userItemRepository;
        this.characterRepository = characterRepository;
    }

    @Transactional(readOnly = true)
    public ThemeListResponse getThemes() {
        return new ThemeListResponse(themeRepository.findByActiveTrueOrderByIdAsc().stream()
                .map(ThemeResponse::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public ItemListResponse getItems(Long userId, Long themeId, PlacementType placementType) {
        Set<Long> ownedItemIds = new HashSet<>(userItemRepository.findActiveItemIdsByUserId(userId));
        return new ItemListResponse(itemRepository.findCatalogItems(themeId, placementType).stream()
                .map(item -> ItemResponse.from(item, ownedItemIds.contains(item.getId())))
                .toList());
    }

    @Transactional(readOnly = true)
    public CharacterListResponse getCharacters() {
        return new CharacterListResponse(characterRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(CharacterResponse::from)
                .toList());
    }
}
