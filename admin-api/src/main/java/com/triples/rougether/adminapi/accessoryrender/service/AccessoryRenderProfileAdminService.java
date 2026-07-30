package com.triples.rougether.adminapi.accessoryrender.service;

import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportRequest;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportResult;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileListResponse;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileRow;
import com.triples.rougether.adminapi.accessoryrender.error.AccessoryRenderProfileInvalidException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.repository.CharacterAccessoryRenderProfileRepository;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessoryRenderProfileAdminService {

    private static final String PLACEMENT_CHARACTER = "character";
    private static final BigDecimal POSITION_MIN = BigDecimal.ZERO;
    private static final BigDecimal POSITION_MAX = BigDecimal.ONE;
    private static final BigDecimal WIDTH_RATIO_MAX = new BigDecimal("2.0000");
    private static final int ROTATION_LIMIT = 360;

    private final ItemRepository itemRepository;
    private final CharacterRepository characterRepository;
    private final CharacterAccessoryRenderProfileRepository renderProfileRepository;

    public AccessoryRenderProfileAdminService(
            ItemRepository itemRepository,
            CharacterRepository characterRepository,
            CharacterAccessoryRenderProfileRepository renderProfileRepository) {
        this.itemRepository = itemRepository;
        this.characterRepository = characterRepository;
        this.renderProfileRepository = renderProfileRepository;
    }

    @Transactional(readOnly = true)
    public AccessoryRenderProfileListResponse list() {
        return new AccessoryRenderProfileListResponse(
                renderProfileRepository.findAllWithItemAndCharacter().stream()
                        .map(AccessoryRenderProfileRow::of)
                        .toList());
    }

    @Transactional
    public AccessoryRenderProfileImportResult importProfiles(
            List<AccessoryRenderProfileImportRequest> requests) {
        if (requests == null) {
            throw invalid("프로필 목록이 필요합니다.");
        }
        validateNoDuplicates(requests);
        int created = 0;
        int updated = 0;
        for (AccessoryRenderProfileImportRequest request : requests) {
            validateValues(request);
            Item item = itemRepository.findByAssetKey(request.itemAssetKey())
                    .orElseThrow(() -> invalid(
                            "아이템을 찾을 수 없습니다: " + request.itemAssetKey()));
            if (!PLACEMENT_CHARACTER.equals(item.getPlacementType())
                    || item.getCharacterSlotType() == null
                    || item.getCharacterSlotType().isBlank()) {
                throw invalid("캐릭터 악세사리 아이템이 아닙니다: " + request.itemAssetKey());
            }
            Character character = characterRepository.findByCode(request.characterCode())
                    .orElseThrow(() -> invalid(
                            "캐릭터를 찾을 수 없습니다: " + request.characterCode()));

            var existing = renderProfileRepository
                    .findByItemIdAndCharacterIdAndRenderState(
                            item.getId(), character.getId(), request.renderState());
            if (existing.isPresent()) {
                update(existing.orElseThrow(), request);
                updated++;
            } else {
                renderProfileRepository.save(new CharacterAccessoryRenderProfile(
                        item,
                        character,
                        request.renderState(),
                        request.assetKey(),
                        request.canvasWidth(),
                        request.canvasHeight(),
                        request.assetWidth(),
                        request.assetHeight(),
                        normalizePosition(request.positionX()),
                        normalizePosition(request.positionY()),
                        request.widthRatio().setScale(4, RoundingMode.HALF_UP),
                        request.rotationDeg(),
                        request.zIndex()));
                created++;
            }
        }
        return new AccessoryRenderProfileImportResult(created, updated);
    }

    private void update(
            CharacterAccessoryRenderProfile profile,
            AccessoryRenderProfileImportRequest request) {
        profile.update(
                request.renderState(),
                request.assetKey(),
                request.canvasWidth(),
                request.canvasHeight(),
                request.assetWidth(),
                request.assetHeight(),
                normalizePosition(request.positionX()),
                normalizePosition(request.positionY()),
                request.widthRatio().setScale(4, RoundingMode.HALF_UP),
                request.rotationDeg(),
                request.zIndex());
    }

    private void validateNoDuplicates(List<AccessoryRenderProfileImportRequest> requests) {
        Set<String> keys = new HashSet<>();
        for (AccessoryRenderProfileImportRequest request : requests) {
            if (request == null) {
                throw invalid("프로필 항목은 null일 수 없습니다.");
            }
            String key = request.itemAssetKey() + "\u0000"
                    + request.characterCode() + "\u0000" + request.renderState();
            if (!keys.add(key)) {
                throw invalid("같은 아이템·캐릭터·상태 프로필이 중복됐습니다.");
            }
        }
    }

    private void validateValues(AccessoryRenderProfileImportRequest request) {
        requireText(request.itemAssetKey(), 255, "itemAssetKey");
        requireText(request.characterCode(), 50, "characterCode");
        requireText(request.renderState(), 40, "renderState");
        requireText(request.assetKey(), 255, "assetKey");
        requirePositive(request.canvasWidth(), "canvasWidth");
        requirePositive(request.canvasHeight(), "canvasHeight");
        requirePositive(request.assetWidth(), "assetWidth");
        requirePositive(request.assetHeight(), "assetHeight");
        validatePosition(request.positionX(), "positionX");
        validatePosition(request.positionY(), "positionY");
        if (request.widthRatio() == null
                || request.widthRatio().compareTo(BigDecimal.ZERO) <= 0
                || request.widthRatio().compareTo(WIDTH_RATIO_MAX) > 0) {
            throw invalid("widthRatio는 0보다 크고 2 이하여야 합니다.");
        }
        if (request.rotationDeg() < -ROTATION_LIMIT
                || request.rotationDeg() > ROTATION_LIMIT) {
            throw invalid("rotationDeg는 -360 이상 360 이하여야 합니다.");
        }
    }

    private void validatePosition(BigDecimal value, String field) {
        if (value == null
                || value.compareTo(POSITION_MIN) < 0
                || value.compareTo(POSITION_MAX) > 0) {
            throw invalid(field + "는 0 이상 1 이하여야 합니다.");
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + "는 비어 있지 않은 " + maxLength + "자 이하 문자열이어야 합니다.");
        }
    }

    private void requirePositive(int value, String field) {
        if (value <= 0) {
            throw invalid(field + "는 양수여야 합니다.");
        }
    }

    private BigDecimal normalizePosition(BigDecimal value) {
        return value.setScale(5, RoundingMode.HALF_UP);
    }

    private AccessoryRenderProfileInvalidException invalid(String message) {
        return new AccessoryRenderProfileInvalidException(message);
    }
}
