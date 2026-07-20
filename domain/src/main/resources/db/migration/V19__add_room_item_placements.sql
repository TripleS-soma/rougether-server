-- 방 가구 자유배치(free placement) 도입 (#162).
-- 자유배치 가구는 room_item_placements 에 저장하고, surface 3종(벽지/바닥/배경)은 기존 room_surface_slots 를 유지한다.
-- personal_rooms.layout_format 이 그 방의 정본을 결정(SLOT_V1=슬롯, FREE_V1=자유배치)하고,
-- layout_revision 은 저장 API 의 baseRevision 낙관적 잠금(다른 기기 덮어쓰기 방지)에 쓴다.
-- 좌표는 방 렌더 영역 전체 기준 0.0~1.0 정규화. 겹침·충돌 검증은 서버가 하지 않는다(팀 확정).

CREATE TABLE room_item_placements (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    room_user_id BIGINT       NOT NULL,
    user_item_id BIGINT       NOT NULL,
    position_x   DECIMAL(6,5) NOT NULL,
    position_y   DECIMAL(6,5) NOT NULL,
    z_index      INT          NOT NULL,
    scale        DECIMAL(4,2) NOT NULL,
    rotation_deg INT          NOT NULL,
    flipped      BOOLEAN      NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_room_item_placement UNIQUE (room_user_id, user_item_id)
);

CREATE INDEX idx_room_item_placement_z ON room_item_placements (room_user_id, z_index);

ALTER TABLE personal_rooms ADD COLUMN layout_format VARCHAR(20) NOT NULL DEFAULT 'SLOT_V1';
ALTER TABLE personal_rooms ADD COLUMN layout_revision INT NOT NULL DEFAULT 0;
