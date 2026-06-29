package com.triples.rougether.adminapi.catalog.service;

import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.CharacterDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ItemDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ThemeDto;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportResult;
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

// 카탈로그를 themes/characters/items 에 적재. 멱등 — 이미 있는 건(theme/character code, item asset_key) skip.
// DB 비밀번호를 코드/사람이 만질 일 없이, 앱이 가진 연결로 적재한다.
@Service
public class CatalogImportService {

    private final ThemeRepository themeRepository;
    private final CharacterRepository characterRepository;
    private final ItemRepository itemRepository;

    public CatalogImportService(ThemeRepository themeRepository,
                                CharacterRepository characterRepository,
                                ItemRepository itemRepository) {
        this.themeRepository = themeRepository;
        this.characterRepository = characterRepository;
        this.itemRepository = itemRepository;
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
            if (itemRepository.existsByAssetKey(i.assetKey())) {
                continue;
            }
            Theme theme = themeByCode.get(i.themeCode());
            if (theme == null) {
                theme = themeRepository.findByCode(i.themeCode())
                        .orElseThrow(() -> new IllegalArgumentException("unknown theme code: " + i.themeCode()));
            }
            itemRepository.save(new Item(
                    theme, i.categoryCode(), i.placementType(),
                    blankToNull(i.surfaceSlotType()), blankToNull(i.characterSlotType()),
                    i.name(), CurrencyType.COIN, i.priceAmount(), i.assetKey(),
                    i.limited(), i.active()));
            itemsCreated++;
        }

        return new CatalogImportResult(themesCreated, charactersCreated, itemsCreated);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
