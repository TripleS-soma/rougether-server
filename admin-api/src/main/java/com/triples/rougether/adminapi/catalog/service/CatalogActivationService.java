package com.triples.rougether.adminapi.catalog.service;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.catalog.dto.CatalogCharacterListResponse;
import com.triples.rougether.adminapi.catalog.dto.CatalogCharacterRow;
import com.triples.rougether.adminapi.catalog.dto.CatalogItemListResponse;
import com.triples.rougether.adminapi.catalog.dto.CatalogItemRow;
import com.triples.rougether.adminapi.catalog.error.CatalogActivationInvalidException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 카탈로그(아이템·캐릭터)의 사용/미사용 토글. 상점·캐릭터 목록·뽑기는 전부 is_active 를
// 조회 시점에 거르므로, 여기서 플래그만 바꾸면 프론트에는 다음 요청부터 바로 반영된다.
// 활성화 시에는 S3 에 실제 에셋이 있는지 검증한다 — 캐릭터 애니메이션 3종은 DB 없이
// code 로 파생되는 계약(user-api CharacterAnimations)이라, 빠뜨린 채 활성화하면
// 프론트에서 조용히 404 가 나기 때문이다 (docs/claude/domains/assets.md).
@Service
public class CatalogActivationService {

    private static final List<String> CHARACTER_ANIMATION_FILES =
            List.of("idle.webp", "pose-cycle.webp", "wave.webp");

    private final ItemRepository itemRepository;
    private final CharacterRepository characterRepository;
    private final AssetStorageService assetStorageService;

    public CatalogActivationService(ItemRepository itemRepository,
                                    CharacterRepository characterRepository,
                                    AssetStorageService assetStorageService) {
        this.itemRepository = itemRepository;
        this.characterRepository = characterRepository;
        this.assetStorageService = assetStorageService;
    }

    @Transactional(readOnly = true)
    public CatalogItemListResponse getItems() {
        return new CatalogItemListResponse(itemRepository.findAllWithTheme().stream()
                .map(CatalogItemRow::of)
                .toList());
    }

    @Transactional(readOnly = true)
    public CatalogCharacterListResponse getCharacters() {
        return new CatalogCharacterListResponse(
                characterRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                        .map(CatalogCharacterRow::of)
                        .toList());
    }

    @Transactional
    public CatalogItemRow updateItemActive(Long itemId, boolean active) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NoSuchElementException("아이템을 찾을 수 없습니다: " + itemId));
        if (active) {
            if (!assetStorageService.exists(item.getAssetKey())) {
                throw new CatalogActivationInvalidException(
                        "S3에서 아이템 에셋을 찾을 수 없어 활성화할 수 없습니다: " + item.getAssetKey());
            }
            item.activate();
        } else {
            item.deactivate();
        }
        return CatalogItemRow.of(item);
    }

    @Transactional
    public CatalogCharacterRow updateCharacterActive(Long characterId, boolean active) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new NoSuchElementException("캐릭터를 찾을 수 없습니다: " + characterId));
        if (active) {
            List<String> missing = missingCharacterAssets(character);
            if (!missing.isEmpty()) {
                throw new CatalogActivationInvalidException(
                        "S3에 캐릭터 에셋이 없어 활성화할 수 없습니다: " + String.join(", ", missing));
            }
            character.activate();
        } else {
            character.deactivate();
        }
        return CatalogCharacterRow.of(character);
    }

    private List<String> missingCharacterAssets(Character character) {
        List<String> missing = new ArrayList<>();
        if (!assetStorageService.exists(character.getBaseAssetKey())) {
            missing.add(character.getBaseAssetKey());
        }
        String animationBase = "characters/" + character.getCode() + "/animations/";
        for (String file : CHARACTER_ANIMATION_FILES) {
            String key = animationBase + file;
            if (!assetStorageService.exists(key)) {
                missing.add(key);
            }
        }
        return missing;
    }
}
