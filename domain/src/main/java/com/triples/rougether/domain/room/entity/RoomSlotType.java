package com.triples.rougether.domain.room.entity;

import java.util.Arrays;

// 방 슬롯 타입 값 집합. Figma 실측(3x3 앵커 그리드) 기준.
// surface 3종 + positioned 8종. topCenter 는 캐릭터(곰/호랑이) 전용이라 슬롯에서 제외.
// DB room_surface_slots.slot_type 은 code 문자열(프론트 FurnitureSlot 과 동일 표기)로 저장.
public enum RoomSlotType {

    // surface 계열 (벽지/바닥/배경)
    WALLPAPER("wallpaper"),
    FLOOR("floor"),
    BACKGROUND("background"),

    // positioned 계열 (가구/소품) — Figma 3x3 앵커. topCenter 는 캐릭터 자리라 없음.
    TOP_LEFT("topLeft"),
    TOP_RIGHT("topRight"),
    MID_LEFT("midLeft"),
    MID_CENTER("midCenter"),
    MID_RIGHT("midRight"),
    BOTTOM_LEFT("bottomLeft"),
    BOTTOM_CENTER("bottomCenter"),
    BOTTOM_RIGHT("bottomRight");

    private final String code;

    RoomSlotType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(type -> type.code.equals(code));
    }
}
