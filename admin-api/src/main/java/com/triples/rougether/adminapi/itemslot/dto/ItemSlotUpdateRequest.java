package com.triples.rougether.adminapi.itemslot.dto;

// 단건 슬롯 변경 요청. slot null/blank 는 슬롯 해제.
public record ItemSlotUpdateRequest(String slot) {
}
