package com.triples.rougether.domain.room.entity;

// 방 배치 데이터의 정본 표시. SLOT_V1 = room_surface_slots(11슬롯), FREE_V1 = room_item_placements(자유배치).
// 자유배치 첫 저장 시 방 단위로 SLOT_V1 → FREE_V1 지연 전환하며, 역방향 전환은 없다.
public enum RoomLayoutFormat {
    SLOT_V1,
    FREE_V1
}
