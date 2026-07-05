package com.triples.rougether.adminapi.itemslot.dto;

// 벌크 적재 한 건 (deploy/seed/slot_assignments.json 형식). name/reason 등 부가 필드는 무시된다.
public record SlotAssignmentDto(String assetKey, String slot) {
}
