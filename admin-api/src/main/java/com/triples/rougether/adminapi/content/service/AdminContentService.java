package com.triples.rougether.adminapi.content.service;

import com.triples.rougether.adminapi.content.web.AdminCharacterRequest;
import com.triples.rougether.adminapi.content.web.AdminCharacterResponse;
import com.triples.rougether.adminapi.content.web.AdminContentCatalogResponse;
import com.triples.rougether.adminapi.content.web.AdminItemRequest;
import com.triples.rougether.adminapi.content.web.AdminItemResponse;
import com.triples.rougether.adminapi.content.web.AdminThemeRequest;
import com.triples.rougether.adminapi.content.web.AdminThemeResponse;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.PlacementType;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminContentService {

    private final ThemeRepository themeRepository;
    private final ItemRepository itemRepository;
    private final CharacterRepository characterRepository;

    public AdminContentService(
            ThemeRepository themeRepository,
            ItemRepository itemRepository,
            CharacterRepository characterRepository) {
        this.themeRepository = themeRepository;
        this.itemRepository = itemRepository;
        this.characterRepository = characterRepository;
    }

    @Transactional(readOnly = true)
    public AdminContentCatalogResponse getCatalog() {
        return new AdminContentCatalogResponse(
                themeRepository.findAll().stream()
                        .map(AdminThemeResponse::from)
                        .toList(),
                itemRepository.findAllByOrderByIdAsc().stream()
                        .map(AdminItemResponse::from)
                        .toList(),
                characterRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                        .map(AdminCharacterResponse::from)
                        .toList());
    }

    @Transactional
    public AdminThemeResponse createTheme(AdminThemeRequest request) {
        assertThemeCodeAvailable(request.code(), null);
        Theme theme = Theme.create(
                normalizeRequired(request.code()),
                normalizeRequired(request.name()),
                normalizeOptional(request.coverImageKey()),
                enabledByDefault(request.active()));
        return AdminThemeResponse.from(themeRepository.save(theme));
    }

    @Transactional
    public AdminThemeResponse updateTheme(Long themeId, AdminThemeRequest request) {
        Theme theme = findTheme(themeId);
        assertThemeCodeAvailable(request.code(), themeId);
        theme.update(
                normalizeRequired(request.code()),
                normalizeRequired(request.name()),
                normalizeOptional(request.coverImageKey()),
                enabledByDefault(request.active()));
        return AdminThemeResponse.from(theme);
    }

    @Transactional
    public AdminItemResponse createItem(AdminItemRequest request) {
        Theme theme = findTheme(request.themeId());
        validatePlacement(request);
        Item item = Item.create(
                theme,
                normalizeRequired(request.categoryCode()),
                request.placementType(),
                surfaceSlotTypeOf(request),
                characterSlotTypeOf(request),
                normalizeRequired(request.name()),
                request.purchaseCurrencyType(),
                request.priceAmount(),
                normalizeRequired(request.assetKey()),
                Boolean.TRUE.equals(request.limited()),
                enabledByDefault(request.active()));
        return AdminItemResponse.from(itemRepository.save(item));
    }

    @Transactional
    public AdminItemResponse updateItem(Long itemId, AdminItemRequest request) {
        Theme theme = findTheme(request.themeId());
        Item item = findItem(itemId);
        validatePlacement(request);
        item.update(
                theme,
                normalizeRequired(request.categoryCode()),
                request.placementType(),
                surfaceSlotTypeOf(request),
                characterSlotTypeOf(request),
                normalizeRequired(request.name()),
                request.purchaseCurrencyType(),
                request.priceAmount(),
                normalizeRequired(request.assetKey()),
                Boolean.TRUE.equals(request.limited()),
                enabledByDefault(request.active()));
        return AdminItemResponse.from(item);
    }

    @Transactional
    public AdminCharacterResponse createCharacter(AdminCharacterRequest request) {
        assertCharacterCodeAvailable(request.code(), null);
        Character character = Character.create(
                normalizeRequired(request.code()),
                normalizeRequired(request.name()),
                normalizeRequired(request.baseAssetKey()),
                request.sortOrder(),
                enabledByDefault(request.active()));
        return AdminCharacterResponse.from(characterRepository.save(character));
    }

    @Transactional
    public AdminCharacterResponse updateCharacter(Long characterId, AdminCharacterRequest request) {
        Character character = findCharacter(characterId);
        assertCharacterCodeAvailable(request.code(), characterId);
        character.update(
                normalizeRequired(request.code()),
                normalizeRequired(request.name()),
                normalizeRequired(request.baseAssetKey()),
                request.sortOrder(),
                enabledByDefault(request.active()));
        return AdminCharacterResponse.from(character);
    }

    private Theme findTheme(Long themeId) {
        return themeRepository.findById(themeId)
                .orElseThrow(() -> notFound("테마를 찾을 수 없습니다: " + themeId));
    }

    private Item findItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> notFound("아이템을 찾을 수 없습니다: " + itemId));
    }

    private Character findCharacter(Long characterId) {
        return characterRepository.findById(characterId)
                .orElseThrow(() -> notFound("캐릭터를 찾을 수 없습니다: " + characterId));
    }

    private void assertThemeCodeAvailable(String code, Long currentId) {
        themeRepository.findByCode(normalizeRequired(code))
                .filter(theme -> !Objects.equals(theme.getId(), currentId))
                .ifPresent(theme -> {
                    throw conflict("이미 존재하는 테마 code 입니다: " + code);
                });
    }

    private void assertCharacterCodeAvailable(String code, Long currentId) {
        characterRepository.findByCode(normalizeRequired(code))
                .filter(character -> !Objects.equals(character.getId(), currentId))
                .ifPresent(character -> {
                    throw conflict("이미 존재하는 캐릭터 code 입니다: " + code);
                });
    }

    private void validatePlacement(AdminItemRequest request) {
        String surfaceSlotType = normalizeOptional(request.surfaceSlotType());
        String characterSlotType = normalizeOptional(request.characterSlotType());
        if (request.placementType() == PlacementType.SURFACE && !StringUtils.hasText(surfaceSlotType)) {
            throw badRequest("SURFACE 아이템은 surfaceSlotType 이 필요합니다.");
        }
        if (request.placementType() == PlacementType.CHARACTER && !StringUtils.hasText(characterSlotType)) {
            throw badRequest("CHARACTER 아이템은 characterSlotType 이 필요합니다.");
        }
        if ((request.purchaseCurrencyType() == null) != (request.priceAmount() == null)) {
            throw badRequest("가격 currency 와 amount 는 함께 입력해야 합니다.");
        }
    }

    private String surfaceSlotTypeOf(AdminItemRequest request) {
        if (request.placementType() == PlacementType.SURFACE) {
            return normalizeOptional(request.surfaceSlotType());
        }
        return null;
    }

    private String characterSlotTypeOf(AdminItemRequest request) {
        if (request.placementType() == PlacementType.CHARACTER) {
            return normalizeOptional(request.characterSlotType());
        }
        return null;
    }

    private boolean enabledByDefault(Boolean active) {
        return active == null || active;
    }

    private String normalizeRequired(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
