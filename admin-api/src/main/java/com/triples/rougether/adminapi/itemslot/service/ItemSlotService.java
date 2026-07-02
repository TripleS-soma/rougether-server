package com.triples.rougether.adminapi.itemslot.service;

import com.triples.rougether.adminapi.itemslot.dto.ItemSlotListResponse;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotRow;
import com.triples.rougether.adminapi.itemslot.dto.SlotAssignmentDto;
import com.triples.rougether.adminapi.itemslot.dto.SlotImportResult;
import com.triples.rougether.domain.room.entity.RoomSlotType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// positioned 아이템의 기본 배치 슬롯(items.default_slot) 관리.
// 단건 변경(어드민 화면) + 벌크 적재(deploy/seed/slot_assignments.json). 적재는 asset_key 매칭이라 멱등.
@Service
public class ItemSlotService {

    private static final String PLACEMENT_POSITIONED = "positioned";

    private final ItemRepository itemRepository;

    public ItemSlotService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public ItemSlotListResponse getPositionedItems() {
        List<ItemSlotRow> rows = itemRepository.findByPlacementTypeWithTheme(PLACEMENT_POSITIONED).stream()
                .map(ItemSlotRow::of)
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
        return ItemSlotRow.of(item);
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
}
