package com.triples.rougether.adminapi.catalog.service;

import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.CharacterDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ItemDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ThemeDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportResult;
import com.triples.rougether.adminapi.itemslot.service.ItemSlotService;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 카탈로그를 themes/characters/items 에 적재. 멱등 — 이미 있는 일반 항목은 skip하고,
// 캐릭터 악세사리는 기존 행도 뽑기 전용·균등 풀 상태로 정규화한다.
// DB 비밀번호를 코드/사람이 만질 일 없이, 앱이 가진 연결로 적재한다.
@Service
public class CatalogImportService {

    private static final String PLACEMENT_CHARACTER = "character";

    private final ThemeRepository themeRepository;
    private final CharacterRepository characterRepository;
    private final ItemRepository itemRepository;
    private final ItemSlotService itemSlotService;

    public CatalogImportService(ThemeRepository themeRepository,
                                CharacterRepository characterRepository,
                                ItemRepository itemRepository,
                                ItemSlotService itemSlotService) {
        this.themeRepository = themeRepository;
        this.characterRepository = characterRepository;
        this.itemRepository = itemRepository;
        this.itemSlotService = itemSlotService;
    }

    @Transactional
    public CatalogImportResult importCatalog(CatalogImportRequest request) {
        Map<String, Theme> themeByCode = new HashMap<>();
        int themesCreated = 0;
        for (ThemeDto t : request.themes()) {
            Theme theme = themeRepository.findByCode(t.code()).orElse(null);
            if (theme == null) {
                theme = themeRepository.save(new Theme(t.code(), t.name(), null, t.active()));
                themesCreated++;
            }
            themeByCode.put(t.code(), theme);
        }

        int charactersCreated = 0;
        for (CharacterDto c : request.characters()) {
            if (characterRepository.findByCode(c.code()).isEmpty()) {
                characterRepository.save(
                        new Character(c.code(), c.name(), c.baseAssetKey(), c.sortOrder(), c.active()));
                charactersCreated++;
            }
        }

        int itemsCreated = 0;
        for (ItemDto i : request.items()) {
            Item existingItem = itemRepository.findByAssetKey(i.assetKey()).orElse(null);
            if (existingItem != null) {
                normalizeCharacterAccessory(existingItem);
                continue;
            }
            Theme theme = themeByCode.get(i.themeCode());
            if (theme == null) {
                theme = themeRepository.findByCode(i.themeCode())
                        .orElseThrow(() -> new IllegalArgumentException("unknown theme code: " + i.themeCode()));
            }
            // 캐릭터 악세사리는 뽑기 전용이므로 입력 가격과 무관하게 구매 정보를 비운다.
            // 나머지 상점 아이템의 구매 재화는 다이아(코인은 루틴 보상·뽑기 전용).
            boolean gachaOnly = PLACEMENT_CHARACTER.equals(i.placementType());
            Item item = itemRepository.save(new Item(
                    theme, i.categoryCode(), i.placementType(),
                    blankToNull(i.surfaceSlotType()), blankToNull(i.characterSlotType()),
                    i.name(), gachaOnly ? null : CurrencyType.DIAMOND,
                    gachaOnly ? null : i.priceAmount(), i.assetKey(),
                    i.limited(), i.active()));
            normalizeCharacterAccessory(item);
            itemsCreated++;
        }

        return new CatalogImportResult(themesCreated, charactersCreated, itemsCreated);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private void normalizeCharacterAccessory(Item item) {
        if (!PLACEMENT_CHARACTER.equals(item.getPlacementType())) {
            return;
        }
        item.makeGachaOnly();
        itemSlotService.registerUniformCharacterAccessory(item);
    }
}
