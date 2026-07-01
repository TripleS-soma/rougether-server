-- room_surface_slots: 한 방(room_user_id)에서 같은 slot_type 은 하나만 존재해야 한다.
-- upsert(findByRoomUserIdAndSlotType) 정합의 최후 방어선이자, 동시 요청 시 중복 행 방지.
ALTER TABLE room_surface_slots
    ADD CONSTRAINT uq_room_surface_slots_room_slot UNIQUE (room_user_id, slot_type);
