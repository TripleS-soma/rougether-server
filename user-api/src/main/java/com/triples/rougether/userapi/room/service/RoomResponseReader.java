package com.triples.rougether.userapi.room.service;

import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.character.repository.UserCharacterAccessoryRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.room.repository.RoomCobwebRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.userapi.character.service.CharacterAccessoryRenderProfileQueryService;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 호출자가 확보한 단일 방의 데이터를 읽어 전체 응답을 조립함. 방 생성·잠금·변경은 호출자가 담당함.
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class RoomResponseReader {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    private final RoomItemPlacementRepository roomItemPlacementRepository;
    private final StreakRepository streakRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserCharacterAccessoryRepository userCharacterAccessoryRepository;
    private final CharacterAccessoryRenderProfileQueryService renderProfileQueryService;
    private final RoomCobwebRepository roomCobwebRepository;
    private final UserRepository userRepository;

    public RoomResponseReader(RoomSurfaceSlotRepository roomSurfaceSlotRepository,
                              RoomItemPlacementRepository roomItemPlacementRepository,
                              StreakRepository streakRepository,
                              UserCharacterRepository userCharacterRepository,
                              UserCharacterAccessoryRepository userCharacterAccessoryRepository,
                              CharacterAccessoryRenderProfileQueryService renderProfileQueryService,
                              RoomCobwebRepository roomCobwebRepository,
                              UserRepository userRepository) {
        this.roomSurfaceSlotRepository = roomSurfaceSlotRepository;
        this.roomItemPlacementRepository = roomItemPlacementRepository;
        this.streakRepository = streakRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.userCharacterAccessoryRepository = userCharacterAccessoryRepository;
        this.renderProfileQueryService = renderProfileQueryService;
        this.roomCobwebRepository = roomCobwebRepository;
        this.userRepository = userRepository;
    }

    // 기존 트랜잭션에 참여하므로 저장 직후 조회의 auto flush와 readOnly 조회의 성격을 유지함.
    public RoomResponse read(PersonalRoom room) {
        Long userId = room.getUserId();
        List<RoomSurfaceSlot> slots = roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId);
        List<RoomItemPlacement> placements = roomItemPlacementRepository.findByRoomUserIdWithItem(userId);
        Streak streak = streakRepository.findByUserId(userId).orElse(null);
        UserCharacter selectedCharacter = userCharacterRepository
                .findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId).orElse(null);
        List<UserCharacterAccessory> accessories = selectedCharacter == null
                ? List.of()
                : userCharacterAccessoryRepository.findActiveByUserCharacterId(
                        selectedCharacter.getId());
        return RoomResponse.of(
                room,
                userRepository.findById(userId).map(User::getBio).orElse(null),
                slots,
                placements,
                streak,
                LocalDate.now(KST),
                selectedCharacter,
                accessories,
                renderProfileQueryService.findFor(accessories),
                roomCobwebRepository.findVisibleActive(userId).orElse(null));
    }
}
