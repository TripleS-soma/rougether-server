package com.triples.rougether.userapi.bot;

import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.PlacementItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 봇 방 자유배치(FREE_V1) 프리셋 3개. 좌표는 방 렌더 영역 0.0~1.0 정규화, 슬롯 8개(가구 시드 상한).
// 시드는 1번을 놓고, 활동 스케줄러(#310)가 매주 1→2→3 으로 순환한다. 지급된 가구 수가 슬롯보다 적으면 앞에서부터 채운다.
public enum BotRoomPreset {

    COZY(List.of(
            slot("0.22", "0.72", 1, "1.10", 0, false),
            slot("0.50", "0.78", 2, "1.20", 0, false),
            slot("0.78", "0.70", 1, "1.00", 0, true),
            slot("0.15", "0.48", 3, "0.90", 0, false),
            slot("0.85", "0.45", 3, "0.90", 0, true),
            slot("0.50", "0.55", 4, "0.80", 0, false),
            slot("0.32", "0.90", 5, "0.70", 0, false),
            slot("0.68", "0.90", 5, "0.70", 0, true))),
    STUDIO(List.of(
            slot("0.30", "0.75", 1, "1.20", 0, false),
            slot("0.70", "0.75", 1, "1.20", 0, true),
            slot("0.50", "0.60", 2, "0.90", 0, false),
            slot("0.12", "0.55", 3, "0.85", 0, false),
            slot("0.88", "0.55", 3, "0.85", 0, true),
            slot("0.50", "0.88", 4, "1.00", 0, false),
            slot("0.25", "0.92", 5, "0.65", 0, false),
            slot("0.75", "0.92", 5, "0.65", 0, true))),
    GARDEN(List.of(
            slot("0.18", "0.80", 1, "1.00", 0, false),
            slot("0.42", "0.72", 2, "1.10", 0, false),
            slot("0.66", "0.80", 1, "1.00", 0, true),
            slot("0.88", "0.62", 3, "0.90", 0, true),
            slot("0.10", "0.50", 3, "0.85", 0, false),
            slot("0.50", "0.45", 4, "0.75", 0, false),
            slot("0.30", "0.93", 5, "0.70", 0, false),
            slot("0.72", "0.93", 5, "0.70", 0, true)));

    public static final int SLOT_COUNT = 8;

    private final List<Slot> slots;

    BotRoomPreset(List<Slot> slots) {
        this.slots = slots;
    }

    public static BotRoomPreset first() {
        return COZY;
    }

    public BotRoomPreset next() {
        BotRoomPreset[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    // 보유 아이템 id 를 슬롯 순서대로 배치 요청으로 변환한다. 아이템이 슬롯보다 많으면 앞 8개만 쓴다.
    public List<PlacementItem> placementsFor(List<Long> userItemIds) {
        List<PlacementItem> placements = new ArrayList<>();
        int count = Math.min(userItemIds.size(), slots.size());
        for (int i = 0; i < count; i++) {
            Slot slot = slots.get(i);
            placements.add(new PlacementItem(userItemIds.get(i), slot.positionX(), slot.positionY(),
                    slot.zIndex(), slot.scale(), slot.rotationDeg(), slot.flipped()));
        }
        return placements;
    }

    private static Slot slot(String x, String y, int zIndex, String scale, int rotationDeg, boolean flipped) {
        return new Slot(new BigDecimal(x), new BigDecimal(y), zIndex, new BigDecimal(scale), rotationDeg, flipped);
    }

    public record Slot(BigDecimal positionX, BigDecimal positionY, int zIndex, BigDecimal scale,
                       int rotationDeg, boolean flipped) {
    }
}
