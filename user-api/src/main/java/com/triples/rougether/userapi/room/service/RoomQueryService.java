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
import com.triples.rougether.userapi.room.dto.RoomRenderResponse;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    // 여러 사용자의 방 렌더 데이터를 한 번에 조회(집 미리보기 등 비구성원 노출 자리).
    // 방 없는 사용자는 결과 map 에 없다 - lazy 생성 없음, streak 같은 활동 정보는 조회하지 않는다(공개 범위 밖).
    // 사용자 수와 무관하게 고정 4쿼리(방·슬롯·자유배치·캐릭터) - 사용자별 반복 조회(N+1)를 만들지 않는다.
    @Transactional(readOnly = true)
    public Map<Long, RoomRenderResponse> findRendersOf(Collection<Long> roomUserIds) {
        if (roomUserIds.isEmpty()) {
            return Map.of();
        }
        List<PersonalRoom> rooms = personalRoomRepository.findAllById(roomUserIds);
        if (rooms.isEmpty()) {
            return Map.of();
        }
        List<Long> withRoom = rooms.stream().map(PersonalRoom::getUserId).toList();
        Map<Long, List<RoomSurfaceSlot>> slotsByUser = roomSurfaceSlotRepository
                .findByRoomUserIdInWithItem(withRoom).stream()
                .collect(Collectors.groupingBy(slot -> slot.getRoom().getUserId()));
        // groupingBy 는 조회 순서를 보존하므로 방별 zIndex 오름차순이 유지된다
        Map<Long, List<RoomItemPlacement>> placementsByUser = roomItemPlacementRepository
                .findByRoomUserIdInWithItem(withRoom).stream()
                .collect(Collectors.groupingBy(placement -> placement.getRoom().getUserId()));
        Map<Long, UserCharacter> characterByUser = userCharacterRepository
                .findSelectedByUserIdIn(withRoom).stream()
                .collect(Collectors.toMap(uc -> uc.getUser().getId(), Function.identity()));
        return rooms.stream().collect(Collectors.toMap(
                PersonalRoom::getUserId,
                room -> RoomRenderResponse.of(
                        room,
                        slotsByUser.getOrDefault(room.getUserId(), List.of()),
                        placementsByUser.getOrDefault(room.getUserId(), List.of()),
                        characterByUser.get(room.getUserId()))));
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
