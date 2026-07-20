package com.triples.rougether.userapi.room.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 방 현황 조회. personal_rooms 가 없으면 첫 방문으로 보고 lazy 생성한다.
@Service
public class RoomQueryService {

    private final PersonalRoomRepository personalRoomRepository;
    private final RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    private final RoomItemPlacementRepository roomItemPlacementRepository;
    private final StreakRepository streakRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserRepository userRepository;

    public RoomQueryService(PersonalRoomRepository personalRoomRepository,
                            RoomSurfaceSlotRepository roomSurfaceSlotRepository,
                            RoomItemPlacementRepository roomItemPlacementRepository,
                            StreakRepository streakRepository,
                            UserCharacterRepository userCharacterRepository,
                            UserRepository userRepository) {
        this.personalRoomRepository = personalRoomRepository;
        this.roomSurfaceSlotRepository = roomSurfaceSlotRepository;
        this.roomItemPlacementRepository = roomItemPlacementRepository;
        this.streakRepository = streakRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.userRepository = userRepository;
    }

    // lazy 생성 가능해야 하므로 readOnly 아님.
    @Transactional
    public RoomResponse getMyRoom(Long userId) {
        PersonalRoom room = personalRoomRepository.findById(userId)
                .orElseGet(() -> personalRoomRepository.save(
                        PersonalRoom.create(userRepository.getReferenceById(userId))));
        return assemble(room, userId);
    }

    // 타인 방 조회(집 멤버 열람 등): 읽기 전용 — 남의 방을 조회로 생성하지 않는다.
    // 인가(같은 집 멤버인지)는 호출자가 검증한다.
    @Transactional(readOnly = true)
    public RoomResponse getRoomOf(Long roomUserId) {
        PersonalRoom room = personalRoomRepository.findById(roomUserId)
                .orElseThrow(() -> new BusinessException(RoomErrorCode.ROOM_NOT_FOUND));
        return assemble(room, roomUserId);
    }

    private RoomResponse assemble(PersonalRoom room, Long userId) {
        List<RoomSurfaceSlot> slots = roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId);
        List<RoomItemPlacement> placements = roomItemPlacementRepository.findByRoomUserIdWithItem(userId);
        Streak streak = streakRepository.findByUserId(userId).orElse(null);
        UserCharacter selectedCharacter = userCharacterRepository
                .findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId).orElse(null);
        return RoomResponse.of(room, slots, placements, streak, selectedCharacter);
    }
}
