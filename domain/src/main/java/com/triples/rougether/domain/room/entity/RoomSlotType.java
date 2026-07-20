package com.triples.rougether.domain.room.entity;

import java.util.Arrays;

// 방 슬롯 타입 값 집합. 프론트 FurnitureSlot(rougether-mobile src/resources/furniture.ts)과 동일 표기.
// surface 3종 + positioned 8종(상단 3 / 중단 좌우 2 / 하단 3). 방 한가운데는 캐릭터(곰/호랑이) 자리라 midCenter 는 없다.
// DB room_surface_slots.slot_type 과 items.default_slot 은 code 문자열로 저장.
public enum RoomSlotType {

    // surface 계열 (벽지/바닥/배경)
    WALLPAPER("wallpaper"),
    FLOOR("floor"),
    BACKGROUND("background"),

    // positioned 계열 (가구/소품) — 캐릭터가 서는 중앙(midCenter)만 제외한 앵커 8종.
    TOP_LEFT("topLeft"),
    TOP_CENTER("topCenter"),
    TOP_RIGHT("topRight"),
    MID_LEFT("midLeft"),
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

    // positioned 가구가 배치될 수 있는 슬롯 코드인지 (items.default_slot 값 검증용).
    public static boolean isPositionedCode(String code) {
        return Arrays.stream(values())
                .filter(type -> type != WALLPAPER && type != FLOOR && type != BACKGROUND)
                .anyMatch(type -> type.code.equals(code));
    }

    // surface(벽지/바닥/배경) 슬롯 코드인지 (자유배치 layout 저장의 surfaceSlots 허용값 검증용).
    public static boolean isSurfaceCode(String code) {
        return WALLPAPER.code.equals(code) || FLOOR.code.equals(code) || BACKGROUND.code.equals(code);
    }
}
