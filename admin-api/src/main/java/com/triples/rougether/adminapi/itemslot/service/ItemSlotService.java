package com.triples.rougether.adminapi.itemslot.service;

import com.triples.rougether.adminapi.itemslot.dto.ItemSlotListResponse;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotRow;
import com.triples.rougether.adminapi.itemslot.dto.RoomPreviewSurfaceListResponse;
import com.triples.rougether.adminapi.itemslot.dto.RoomPreviewSurfaceRow;
import com.triples.rougether.adminapi.itemslot.dto.SlotAssignmentDto;
import com.triples.rougether.adminapi.itemslot.dto.SlotImportResult;
import com.triples.rougether.adminapi.itemslot.error.ItemDefaultScaleInvalidException;
import com.triples.rougether.adminapi.itemslot.error.ItemRarityInvalidException;
import com.triples.rougether.adminapi.itemslot.error.ItemRenderDefaultsInvalidException;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaCategory;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.room.entity.RoomSlotType;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// positioned 아이템의 기본 배치 슬롯(items.default_slot)과 세 카테고리의 뽑기 등급 관리.
// 단건 변경(어드민 화면) + 벌크 적재(deploy/seed/slot_assignments.json). 적재는 asset_key 매칭이라 멱등.
@Service
public class ItemSlotService {

    private static final String PLACEMENT_POSITIONED = "positioned";
    private static final String PLACEMENT_SURFACE = "surface_slot";
    private static final BigDecimal MIN_DEFAULT_SCALE = new BigDecimal("0.50");
    private static final BigDecimal MAX_DEFAULT_SCALE = new BigDecimal("2.00");
    private static final BigDecimal MIN_DEFAULT_POSITION = BigDecimal.ZERO;
    private static final BigDecimal MAX_DEFAULT_POSITION = BigDecimal.ONE;
    private static final String DEFAULT_SCALE_RANGE_MESSAGE =
            "기본 크기 배율은 0.50 이상 2.00 이하의 숫자여야 합니다.";
    private static final String DEFAULT_POSITION_PAIR_MESSAGE =
            "기본 위치 X와 Y는 둘 다 입력하거나 둘 다 비워야 합니다.";
    private static final String DEFAULT_POSITION_RANGE_MESSAGE =
            "기본 위치 X와 Y는 0 이상 1 이하의 숫자여야 합니다.";
    // 가구(테마) 뽑기 단가 — spec domains/gacha/api.md (5+1회 = x5 는 user-api GachaService 가 계산)
    private static final int ITEM_GACHA_COST_COIN = 25;
    private static final int GACHA_CODE_MAX_LENGTH = 50;
    private static final String ACCESSORY_GACHA_CODE_SUFFIX = "_accessories";

    private final ItemRepository itemRepository;
    private final ThemeRepository themeRepository;
    private final GachaRepository gachaRepository;
    private final GachaPoolEntryRepository gachaPoolEntryRepository;

    public ItemSlotService(ItemRepository itemRepository,
                           ThemeRepository themeRepository,
                           GachaRepository gachaRepository,
                           GachaPoolEntryRepository gachaPoolEntryRepository) {
        this.itemRepository = itemRepository;
        this.themeRepository = themeRepository;
        this.gachaRepository = gachaRepository;
        this.gachaPoolEntryRepository = gachaPoolEntryRepository;
    }

    @Transactional(readOnly = true)
    public ItemSlotListResponse getPositionedItems() {
        List<Item> items = itemRepository.findByPlacementTypeWithTheme(PLACEMENT_POSITIONED);
        Map<Long, List<GachaPoolEntry>> entriesByItemId = activeItemEntriesByItemId(
                items.stream().map(Item::getId).toList());
        List<ItemSlotRow> rows = items.stream()
                .map(item -> ItemSlotRow.of(item, entriesByItemId.getOrDefault(item.getId(), List.of())))
                .toList();
        return new ItemSlotListResponse(rows);
    }

    @Transactional(readOnly = true)
    public RoomPreviewSurfaceListResponse getActiveSurfaceItems() {
        List<RoomPreviewSurfaceRow> rows = itemRepository.findByPlacementTypeWithTheme(PLACEMENT_SURFACE).stream()
                .filter(item -> item.isActive() && item.getTheme().isActive())
                .map(RoomPreviewSurfaceRow::of)
                .toList();
        return new RoomPreviewSurfaceListResponse(rows);
    }

    @Transactional
    public ItemSlotRow updateSlot(Long itemId, String slot) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("item 이 없습니다: " + itemId));
        if (!PLACEMENT_POSITIONED.equals(item.getPlacementType())) {
            throw new IllegalArgumentException("positioned 아이템이 아닙니다: " + itemId);
        }
        String normalized = blankToNull(slot);
        if (normalized != null && !RoomSlotType.isPositionedCode(normalized)) {
            throw new IllegalArgumentException("positioned 슬롯 코드가 아닙니다: " + slot);
        }
        item.updateDefaultSlot(normalized);
        return ItemSlotRow.of(item, findActiveItemEntries(itemId));
    }

    @Transactional
    public ItemSlotRow updateDefaultScale(Long itemId, BigDecimal defaultScale) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ItemDefaultScaleInvalidException("아이템을 찾을 수 없습니다: " + itemId));
        if (!PLACEMENT_POSITIONED.equals(item.getPlacementType())) {
            throw new ItemDefaultScaleInvalidException(
                    "positioned 아이템만 기본 크기 배율을 변경할 수 있습니다.");
        }
        if (defaultScale == null
                || defaultScale.compareTo(MIN_DEFAULT_SCALE) < 0
                || defaultScale.compareTo(MAX_DEFAULT_SCALE) > 0) {
            throw new ItemDefaultScaleInvalidException(DEFAULT_SCALE_RANGE_MESSAGE);
        }

        item.updateDefaultScale(defaultScale.setScale(2, RoundingMode.HALF_UP));
        return ItemSlotRow.of(item, findActiveItemEntries(itemId));
    }

    @Transactional
    public ItemSlotRow updateRenderDefaults(Long itemId,
                                            BigDecimal defaultScale,
                                            BigDecimal defaultPositionX,
                                            BigDecimal defaultPositionY) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ItemRenderDefaultsInvalidException("아이템을 찾을 수 없습니다: " + itemId));
        if (!PLACEMENT_POSITIONED.equals(item.getPlacementType())) {
            throw new ItemRenderDefaultsInvalidException(
                    "positioned 아이템만 FREE 기본 렌더링 값을 변경할 수 있습니다.");
        }

        validateDefaultScale(defaultScale);
        validateDefaultPosition(defaultPositionX, defaultPositionY);

        BigDecimal normalizedScale = defaultScale.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedX = normalizePosition(defaultPositionX);
        BigDecimal normalizedY = normalizePosition(defaultPositionY);
        item.updateRenderDefaults(normalizedScale, normalizedX, normalizedY);
        return ItemSlotRow.of(item, findActiveItemEntries(itemId));
    }

    @Transactional
    public ItemSlotRow updateRarity(Long itemId, String rarity) {
        if (!GachaRarity.isSupported(rarity)) {
            throw new ItemRarityInvalidException("허용되지 않은 뽑기 등급입니다: " + rarity);
        }

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ItemRarityInvalidException("item 이 없습니다: " + itemId));
        GachaCategory category = GachaCategory.fromItem(item);
        if (category == null) {
            throw new ItemRarityInvalidException("벽지·바닥·가구 뽑기 등급 관리 대상이 아닙니다: " + itemId);
        }
        Gacha gacha = lockActiveCategoryGacha(category);
        List<GachaPoolEntry> entries =
                gachaPoolEntryRepository.findItemEntriesByGachaIdAndItemIdForUpdate(gacha.getId(), itemId);
        List<GachaPoolEntry> activeItemEntries = entries.stream().filter(GachaPoolEntry::isActive).toList();
        if (entries.isEmpty()) {
            validateActiveContent(item);
            activeItemEntries = List.of(gachaPoolEntryRepository.save(
                    GachaPoolEntry.itemEntry(gacha, item, rarity)));
        } else if (activeItemEntries.isEmpty()) {
            throw new ItemRarityInvalidException("중지된 뽑기 풀입니다. 풀 운영 상태를 먼저 확인해 주세요: " + itemId);
        } else {
            activeItemEntries.forEach(entry -> entry.updateRarity(rarity));
        }
        gachaPoolEntryRepository.flush();
        return ItemSlotRow.of(item, activeItemEntries);
    }

    // 새 카탈로그 아이템은 전역 카테고리 머신으로 연결한다. 재적재는 등급과 중지 상태를 보존한다.
    // 카테고리 머신 행 락으로 서로 다른 테마의 동시 등록까지 직렬화하고, 락 이후 최신 풀을 재조회한다.
    @Transactional
    public void registerCategoryItem(Item item) {
        GachaCategory category = GachaCategory.fromItem(item);
        if (category == null || !item.isActive() || !item.getTheme().isActive()) {
            return;
        }
        Gacha gacha = lockActiveCategoryGacha(category);
        List<GachaPoolEntry> entries = gachaPoolEntryRepository
                .findItemEntriesByGachaIdAndItemIdForUpdate(gacha.getId(), item.getId());
        if (entries.isEmpty()) {
            gachaPoolEntryRepository.save(GachaPoolEntry.itemEntry(gacha, item, GachaRarity.NORMAL));
        }
    }

    private Gacha lockActiveCategoryGacha(GachaCategory category) {
        Gacha gacha = gachaRepository.findByCodeForUpdate(category.getCode())
                .orElseThrow(() -> new ItemRarityInvalidException(
                        "카테고리 뽑기 머신이 없습니다. 뽑기 카테고리 마이그레이션을 확인해 주세요: " + category.getCode()));
        if (!gacha.isActive()) {
            throw new ItemRarityInvalidException(
                    "카테고리 뽑기 머신이 중지되어 있습니다. 운영 상태를 확인해 주세요: " + category.getCode());
        }
        return gacha;
    }

    private void validateActiveContent(Item item) {
        if (!item.isActive() || !item.getTheme().isActive()) {
            throw new ItemRarityInvalidException("비활성 아이템/테마는 뽑기 풀에 등록할 수 없습니다: " + item.getId());
        }
    }

    // 캐릭터 악세사리는 카탈로그 적재 시 자동 등록한다. 등급을 두지 않고 모든 엔트리를 weight=1로 통일한다.
    @Transactional
    public void registerUniformCharacterAccessory(Item item) {
        registerUniformToThemeGachas(item);
        gachaPoolEntryRepository.flush();
    }

    private List<GachaPoolEntry> registerUniformToThemeGachas(Item item) {
        if (!item.isActive() || !item.getTheme().isActive()) {
            return List.of();
        }
        Theme theme = themeRepository.findWithLockById(item.getTheme().getId())
                .orElseThrow(() -> new ItemRarityInvalidException(
                        "theme 이 없습니다: " + item.getTheme().getId()));

        List<GachaPoolEntry> alreadyRegistered =
                gachaPoolEntryRepository.findActiveItemEntriesForUpdate(item.getId());
        String accessoryGachaCode = accessoryGachaCode(theme.getCode());
        List<GachaPoolEntry> dedicatedEntries = alreadyRegistered.stream()
                .filter(entry -> accessoryGachaCode.equals(entry.getGacha().getCode()))
                .toList();
        alreadyRegistered.stream()
                .filter(entry -> !accessoryGachaCode.equals(entry.getGacha().getCode()))
                .forEach(GachaPoolEntry::deactivate);
        if (!dedicatedEntries.isEmpty()) {
            dedicatedEntries.forEach(GachaPoolEntry::configureUniformDistribution);
            return dedicatedEntries;
        }

        Gacha accessoryGacha = gachaRepository
                .findActiveByThemeIdAndCodeForUpdate(theme.getId(), accessoryGachaCode)
                .orElseGet(() -> gachaRepository.save(new Gacha(
                        accessoryGachaCode, theme.getName() + " 악세사리 뽑기",
                        CurrencyType.COIN, ITEM_GACHA_COST_COIN, 1, theme, true)));
        return List.of(gachaPoolEntryRepository.save(
                GachaPoolEntry.uniformItemEntry(accessoryGacha, item)));
    }

    private static String accessoryGachaCode(String themeCode) {
        int prefixLength = GACHA_CODE_MAX_LENGTH - ACCESSORY_GACHA_CODE_SUFFIX.length();
        String prefix = themeCode.length() <= prefixLength
                ? themeCode
                : themeCode.substring(0, prefixLength);
        return prefix + ACCESSORY_GACHA_CODE_SUFFIX;
    }

    @Transactional
    public SlotImportResult importSlots(List<SlotAssignmentDto> assignments) {
        int applied = 0;
        List<String> notFound = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (SlotAssignmentDto assignment : assignments) {
            String slot = blankToNull(assignment.slot());
            if (slot == null || !RoomSlotType.isPositionedCode(slot)) {
                invalid.add(assignment.assetKey());
                continue;
            }
            Item item = itemRepository.findByAssetKey(assignment.assetKey()).orElse(null);
            if (item == null) {
                notFound.add(assignment.assetKey());
                continue;
            }
            if (!PLACEMENT_POSITIONED.equals(item.getPlacementType())) {
                invalid.add(assignment.assetKey());
                continue;
            }
            item.updateDefaultSlot(slot);
            applied++;
        }
        return new SlotImportResult(applied, List.copyOf(notFound), List.copyOf(invalid));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static void validateDefaultScale(BigDecimal defaultScale) {
        if (defaultScale == null
                || defaultScale.compareTo(MIN_DEFAULT_SCALE) < 0
                || defaultScale.compareTo(MAX_DEFAULT_SCALE) > 0) {
            throw new ItemRenderDefaultsInvalidException(DEFAULT_SCALE_RANGE_MESSAGE);
        }
    }

    private static void validateDefaultPosition(BigDecimal x, BigDecimal y) {
        if (x == null || y == null) {
            if (x != null || y != null) {
                throw new ItemRenderDefaultsInvalidException(DEFAULT_POSITION_PAIR_MESSAGE);
            }
            return;
        }
        if (x.compareTo(MIN_DEFAULT_POSITION) < 0
                || x.compareTo(MAX_DEFAULT_POSITION) > 0
                || y.compareTo(MIN_DEFAULT_POSITION) < 0
                || y.compareTo(MAX_DEFAULT_POSITION) > 0) {
            throw new ItemRenderDefaultsInvalidException(DEFAULT_POSITION_RANGE_MESSAGE);
        }
    }

    private static BigDecimal normalizePosition(BigDecimal position) {
        return position == null ? null : position.setScale(5, RoundingMode.HALF_UP);
    }

    private List<GachaPoolEntry> findActiveItemEntries(Long itemId) {
        return gachaPoolEntryRepository.findActiveItemEntriesByItemIds(List.of(itemId)).stream()
                .filter(ItemSlotService::isCanonicalCategoryEntry)
                .toList();
    }

    private Map<Long, List<GachaPoolEntry>> activeItemEntriesByItemId(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return gachaPoolEntryRepository.findActiveItemEntriesByItemIds(itemIds).stream()
                .filter(ItemSlotService::isCanonicalCategoryEntry)
                .collect(Collectors.groupingBy(entry -> entry.getItem().getId()));
    }

    private static boolean isCanonicalCategoryEntry(GachaPoolEntry entry) {
        GachaCategory category = GachaCategory.fromItem(entry.getItem());
        return category != null && category.getCode().equals(entry.getGacha().getCode());
    }
}
