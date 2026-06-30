package com.triples.rougether.userapi.room.service;

import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 방 현황 조회. personal_rooms 가 없으면 첫 방문으로 보고 lazy 생성한다.
@Service
public class RoomQueryService {

    private final PersonalRoomRepository personalRoomRepository;
    private final RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    private final StreakRepository streakRepository;
    private final UserRepository userRepository;

    public RoomQueryService(PersonalRoomRepository personalRoomRepository,
                            RoomSurfaceSlotRepository roomSurfaceSlotRepository,
                            StreakRepository streakRepository,
                            UserRepository userRepository) {
        this.personalRoomRepository = personalRoomRepository;
        this.roomSurfaceSlotRepository = roomSurfaceSlotRepository;
        this.streakRepository = streakRepository;
        this.userRepository = userRepository;
    }

    // lazy 생성 가능해야 하므로 readOnly 아님.
    @Transactional
    public RoomResponse getMyRoom(Long userId) {
        PersonalRoom room = personalRoomRepository.findById(userId)
                .orElseGet(() -> personalRoomRepository.save(
                        PersonalRoom.create(userRepository.getReferenceById(userId))));

        List<RoomSurfaceSlot> slots = roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId);
        Streak streak = streakRepository.findByUserId(userId).orElse(null);

        return RoomResponse.of(room, slots, streak);
    }
}
