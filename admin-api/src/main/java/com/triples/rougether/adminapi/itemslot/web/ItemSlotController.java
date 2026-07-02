package com.triples.rougether.adminapi.itemslot.web;

import com.triples.rougether.adminapi.itemslot.dto.ItemSlotListResponse;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotRow;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotUpdateRequest;
import com.triples.rougether.adminapi.itemslot.dto.SlotAssignmentDto;
import com.triples.rougether.adminapi.itemslot.dto.SlotImportResult;
import com.triples.rougether.adminapi.itemslot.service.ItemSlotService;
import com.triples.rougether.common.error.ErrorResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 어드민 아이템 기본 슬롯 관리. 목록/단건 변경은 편집 화면(/item-slots)이, 벌크 적재는 seed 스크립트(curl)가 호출.
@RestController
@RequestMapping("/admin/items")
public class ItemSlotController {

    private final ItemSlotService itemSlotService;

    public ItemSlotController(ItemSlotService itemSlotService) {
        this.itemSlotService = itemSlotService;
    }

    @GetMapping("/slots")
    public ItemSlotListResponse getSlots() {
        return itemSlotService.getPositionedItems();
    }

    @PutMapping("/{itemId}/slot")
    public ItemSlotRow updateSlot(@PathVariable Long itemId,
                                  @RequestBody ItemSlotUpdateRequest request) {
        return itemSlotService.updateSlot(itemId, request.slot());
    }

    @PostMapping("/slots/import")
    public SlotImportResult importSlots(@RequestBody List<SlotAssignmentDto> assignments) {
        return itemSlotService.importSlots(assignments);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("ITEM_SLOT_INVALID", exception.getMessage()));
    }
}
