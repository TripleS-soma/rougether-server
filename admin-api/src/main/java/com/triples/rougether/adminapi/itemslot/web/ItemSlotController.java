package com.triples.rougether.adminapi.itemslot.web;

import com.triples.rougether.adminapi.itemslot.dto.ItemDefaultScaleUpdateRequest;
import com.triples.rougether.adminapi.itemslot.dto.ItemRarityUpdateRequest;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotListResponse;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotRow;
import com.triples.rougether.adminapi.itemslot.dto.ItemSlotUpdateRequest;
import com.triples.rougether.adminapi.itemslot.dto.RoomPreviewSurfaceListResponse;
import com.triples.rougether.adminapi.itemslot.dto.SlotAssignmentDto;
import com.triples.rougether.adminapi.itemslot.dto.SlotImportResult;
import com.triples.rougether.adminapi.itemslot.error.ItemDefaultScaleInvalidException;
import com.triples.rougether.adminapi.itemslot.error.ItemRarityInvalidException;
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

// 어드민 가구 관리. 화면(/item-slots)에서 기본 슬롯과 뽑기 등급을 변경하고,
// 기본 슬롯 벌크 적재는 seed 스크립트(curl)가 호출한다.
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

    @GetMapping("/surfaces")
    public RoomPreviewSurfaceListResponse getSurfaces() {
        return itemSlotService.getActiveSurfaceItems();
    }

    @PutMapping("/{itemId}/slot")
    public ItemSlotRow updateSlot(@PathVariable Long itemId,
                                  @RequestBody ItemSlotUpdateRequest request) {
        return itemSlotService.updateSlot(itemId, request.slot());
    }

    @PutMapping("/{itemId}/default-scale")
    public ItemSlotRow updateDefaultScale(@PathVariable Long itemId,
                                          @RequestBody ItemDefaultScaleUpdateRequest request) {
        return itemSlotService.updateDefaultScale(itemId, request.defaultScale());
    }

    @PutMapping("/{itemId}/rarity")
    public ItemSlotRow updateRarity(@PathVariable Long itemId,
                                    @RequestBody ItemRarityUpdateRequest request) {
        return itemSlotService.updateRarity(itemId, request.rarity());
    }

    @PostMapping("/slots/import")
    public SlotImportResult importSlots(@RequestBody List<SlotAssignmentDto> assignments) {
        return itemSlotService.importSlots(assignments);
    }

    @ExceptionHandler(ItemDefaultScaleInvalidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDefaultScale(ItemDefaultScaleInvalidException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("ITEM_DEFAULT_SCALE_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(ItemRarityInvalidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRarity(ItemRarityInvalidException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("ITEM_RARITY_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("ITEM_SLOT_INVALID", exception.getMessage()));
    }
}
