package com.triples.rougether.domain.shop.entity;

// 잠정 값 — 상점 도메인(장진형) 확정 시 조정 필요.
// surface_slot_type / character_slot_type 중 어느 쪽 슬롯에 놓이는지를 구분.
public enum PlacementType {
    SURFACE,   // 방 표면 슬롯 배치 (surface_slot_type 사용)
    CHARACTER  // 캐릭터 슬롯 착용 (character_slot_type 사용)
}
