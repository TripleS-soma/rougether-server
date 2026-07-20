package com.triples.rougether.userapi.room.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RoomErrorCode implements ErrorCode {

    INVALID_SLOT_TYPE("ROOM_INVALID_SLOT_TYPE", "유효하지 않은 슬롯 타입입니다.", 400),
    DUPLICATE_SLOT_TYPE("ROOM_DUPLICATE_SLOT_TYPE", "같은 슬롯을 한 요청에 중복 지정할 수 없습니다.", 400),
    ITEM_NOT_OWNED("ROOM_ITEM_NOT_OWNED", "소유하지 않은 아이템은 배치할 수 없습니다.", 403),
    // 타인 방 조회 전용. 내 방(GET /rooms/me)은 lazy 생성이라 이 에러가 나지 않는다
    ROOM_NOT_FOUND("ROOM_NOT_FOUND", "아직 방을 만들지 않은 회원입니다.", 404),
    // 자유배치(layout) 저장 전용
    INVALID_PLACEMENT("ROOM_INVALID_PLACEMENT", "배치 값이 허용 범위를 벗어났습니다.", 400),
    DUPLICATE_PLACEMENT_ITEM("ROOM_DUPLICATE_PLACEMENT_ITEM", "같은 보유 아이템을 한 요청에 중복 배치할 수 없습니다.", 400),
    LAYOUT_REVISION_CONFLICT("ROOM_LAYOUT_REVISION_CONFLICT", "다른 기기에서 방이 먼저 저장되었습니다. 최신 상태를 다시 불러와 주세요.", 409),
    // FREE_V1 전환 방에 구버전 슬롯 저장(positioned 포함)이 들어온 경우 — 자유배치 데이터 보존을 위해 거부
    LAYOUT_FORMAT_CONFLICT("ROOM_LAYOUT_FORMAT_CONFLICT", "자유배치로 전환된 방은 슬롯 방식으로 가구를 저장할 수 없습니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    RoomErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int status() {
        return status;
    }
}
