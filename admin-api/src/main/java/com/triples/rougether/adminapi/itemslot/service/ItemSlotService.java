package com.triples.rougether.adminapi.itemslot.service;

import com.triples.rougether.adminapi.itemslot.dto.ItemSlotListResponse;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotRow;
import com.triples.rougether.adminapi.itemslot.dto.RoomPreviewSurfaceRow;
import com.triples.rougether.adminapi.itemslot.dto.SlotAssignmentDto;
import com.triples.rougether.adminapi.itemslot.dto.SlotImportResult;
import com.triples.rougether.adminapi.itemslot.error.ItemDefaultScaleInvalidException;
import com.triples.rougether.adminapi.itemslot.error.ItemRarityInvalidException;
import com.triples.rougether.domain.gacha.entity.Gacha;
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

// positioned 아이템의 기본 배치 슬롯(items.default_slot)과 뽑기 등급 관리.
// 단건 변경(어드민 화면) + 벌크 적재(deploy/seed/slot_assignments.json). 적재는 asset_key 매칭이라 멱등.
@Service
public class ItemSlotService {

    private static final String PLACEMENT_POSITIONED = "positioned";
    private static final String PLACEMENT_SURFACE = "surface_slot";
    private static final BigDecimal MIN_DEFAULT_SCALE = new BigDecimal("0.50");
    private static final BigDecimal MAX_DEFAULT_SCALE = new BigDecimal("2.00");
    private static final String DEFAULT_SCALE_RANGE_MESSAGE =
            "기본 크기 배율은 0.50 이상 2.00 이하의 숫자여야 합니다.";
    // 가구(테마) 뽑기 단가 — spec domains/gacha/api.md (10연 = x5 는 user-api GachaService 가 계산)
    private static final int ITEM_GACHA_COST_COIN = 250;

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
    public List<RoomPreviewSurfaceRow> getActiveSurfaceItems() {
        return itemRepository.findByPlacementTypeWithTheme(PLACEMENT_SURFACE).stream()
                .filter(Item::isActive)
                .map(RoomPreviewSurfaceRow::of)
                .toList();
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
    public ItemSlotRow updateRarity(Long itemId, String rarity) {
        if (!GachaRarity.isSupported(rarity)) {
            throw new ItemRarityInvalidException("허용되지 않은 뽑기 등급입니다: " + rarity);
        }

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ItemRarityInvalidException("item 이 없습니다: " + itemId));
        if (!PLACEMENT_POSITIONED.equals(item.getPlacementType())) {
            throw new ItemRarityInvalidException("positioned 아이템이 아닙니다: " + itemId);
        }

        List<GachaPoolEntry> activeItemEntries = findActiveItemEntries(itemId);
        if (activeItemEntries.isEmpty()) {
            activeItemEntries = registerToThemeGachas(item, rarity);
        } else {
            activeItemEntries.forEach(entry -> entry.updateRarity(rarity));
        }
        gachaPoolEntryRepository.flush();
        return ItemSlotRow.of(item, activeItemEntries);
    }

    // 미등록 아이템을 테마의 활성 머신 전부에 등록. 머신이 없으면 스펙 기본값(COIN 250, 1회)으로 새로 만듦.
    // 테마 행 락으로 동시 등록을 직렬화 — 같은 테마의 연속 클릭이 겹쳐도 머신/엔트리가 중복 생성되지 않는다.
    // 락 이후 재확인은 locking read 로만 한다 — REPEATABLE READ 에선 일반 조회가 락 이전 스냅샷을 읽어
    // 선행 커밋(머신/엔트리)을 못 보고 중복 생성할 수 있다.
    private List<GachaPoolEntry> registerToThemeGachas(Item item, String rarity) {
        // 비활성 콘텐츠가 유료 뽑기로 배출되지 않게 등록 자체를 거부 (기존 엔트리의 등급 변경은 허용)
        if (!item.isActive() || !item.getTheme().isActive()) {
            throw new ItemRarityInvalidException("비활성 아이템/테마는 뽑기 풀에 등록할 수 없습니다: " + item.getId());
        }
        Theme theme = themeRepository.findWithLockById(item.getTheme().getId())
                .orElseThrow(() -> new ItemRarityInvalidException("theme 이 없습니다: " + item.getTheme().getId()));

        // 락 대기 중 다른 요청이 먼저 등록했을 수 있으므로 재확인
        List<GachaPoolEntry> alreadyRegistered =
                gachaPoolEntryRepository.findActiveItemEntriesForUpdate(item.getId());
        if (!alreadyRegistered.isEmpty()) {
            alreadyRegistered.forEach(entry -> entry.updateRarity(rarity));
            return alreadyRegistered;
        }

        List<Gacha> themeGachas = gachaRepository.findActiveByThemeIdForUpdate(theme.getId());
        if (themeGachas.isEmpty()) {
            themeGachas = List.of(gachaRepository.save(new Gacha(
                    theme.getCode(), theme.getName() + " 뽑기",
                    CurrencyType.COIN, ITEM_GACHA_COST_COIN, 1, theme, true)));
        }
        List<GachaPoolEntry> entries = themeGachas.stream()
                .map(gacha -> GachaPoolEntry.itemEntry(gacha, item, rarity))
                .toList();
        return gachaPoolEntryRepository.saveAll(entries);
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

    private List<GachaPoolEntry> findActiveItemEntries(Long itemId) {
        return gachaPoolEntryRepository.findActiveItemEntriesByItemIds(List.of(itemId));
    }

    private Map<Long, List<GachaPoolEntry>> activeItemEntriesByItemId(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return gachaPoolEntryRepository.findActiveItemEntriesByItemIds(itemIds).stream()
                .collect(Collectors.groupingBy(entry -> entry.getItem().getId()));
    }
}
