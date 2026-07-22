package com.triples.rougether.adminapi.itemslot.service;

import com.triples.rougether.adminapi.itemslot.dto.ItemSlotListResponse;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotRow;
import com.triples.rougether.adminapi.itemslot.dto.SlotAssignmentDto;
import com.triples.rougether.adminapi.itemslot.dto.SlotImportResult;
import com.triples.rougether.adminapi.itemslot.error.ItemRarityInvalidException;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.room.entity.RoomSlotType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// positioned 아이템의 기본 배치 슬롯(items.default_slot) 관리.
// 단건 변경(어드민 화면) + 벌크 적재(deploy/seed/slot_assignments.json). 적재는 asset_key 매칭이라 멱등.
@Service
public class ItemSlotService {

    private static final String PLACEMENT_POSITIONED = "positioned";

    private final ItemRepository itemRepository;
    private final GachaPoolEntryRepository gachaPoolEntryRepository;

    public ItemSlotService(ItemRepository itemRepository,
                           GachaPoolEntryRepository gachaPoolEntryRepository) {
        this.itemRepository = itemRepository;
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
            throw new ItemRarityInvalidException("활성 ITEM 뽑기 풀에 등록되지 않은 아이템입니다: " + itemId);
        }
        activeItemEntries.forEach(entry -> entry.updateRarity(rarity));
        gachaPoolEntryRepository.flush();
        return ItemSlotRow.of(item, activeItemEntries);
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
