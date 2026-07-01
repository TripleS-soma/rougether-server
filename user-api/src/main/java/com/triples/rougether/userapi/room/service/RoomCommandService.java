package com.triples.rougether.userapi.room.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomSlotType;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 방 슬롯 배치 저장. slot_type 값 검증 + 아이템 소유 검증 후 upsert.
// 방이 없으면 조회와 동일하게 첫 방문으로 보고 lazy 생성한다.
@Service
public class RoomCommandService {

    private final PersonalRoomRepository personalRoomRepository;
    private final RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    private final UserItemRepository userItemRepository;
    private final StreakRepository streakRepository;
    private final UserRepository userRepository;

    public RoomCommandService(PersonalRoomRepository personalRoomRepository,
                              RoomSurfaceSlotRepository roomSurfaceSlotRepository,
                              UserItemRepository userItemRepository,
                              StreakRepository streakRepository,
                              UserRepository userRepository) {
        this.personalRoomRepository = personalRoomRepository;
        this.roomSurfaceSlotRepository = roomSurfaceSlotRepository;
        this.userItemRepository = userItemRepository;
        this.streakRepository = streakRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RoomResponse updateSlots(Long userId, RoomSlotUpdateRequest request) {
        validateNoDuplicateSlotType(request);

        PersonalRoom room = personalRoomRepository.findById(userId)
                .orElseGet(() -> personalRoomRepository.save(
                        PersonalRoom.create(userRepository.getReferenceById(userId))));

        // 소유 아이템 맵(id -> UserItem). 배치 대상 소유 검증 + 참조 확보에 재사용.
        Map<Long, UserItem> ownedItems = userItemRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .collect(Collectors.toMap(UserItem::getId, Function.identity()));

        for (RoomSlotUpdateRequest.SlotAssignment assignment : request.slots()) {
            applyAssignment(userId, room, ownedItems, assignment);
        }

        List<RoomSurfaceSlot> slots = roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId);
        Streak streak = streakRepository.findByUserId(userId).orElse(null);
        return RoomResponse.of(room, slots, streak);
    }

    // 같은 slotType 을 한 요청에 두 번 지정하면 upsert 결과가 모호 → 진입 시 거부.
    // (DB UNIQUE(room_user_id, slot_type) 가 최후 방어선, 이건 요청 단위 조기 거부.)
    private void validateNoDuplicateSlotType(RoomSlotUpdateRequest request) {
        long distinct = request.slots().stream()
                .map(RoomSlotUpdateRequest.SlotAssignment::slotType)
                .distinct()
                .count();
        if (distinct != request.slots().size()) {
            throw new BusinessException(RoomErrorCode.DUPLICATE_SLOT_TYPE);
        }
    }

    private void applyAssignment(Long userId, PersonalRoom room, Map<Long, UserItem> ownedItems,
                                 RoomSlotUpdateRequest.SlotAssignment assignment) {
        String slotType = assignment.slotType();
        if (!RoomSlotType.isValid(slotType)) {
            throw new BusinessException(RoomErrorCode.INVALID_SLOT_TYPE);
        }

        Long userItemId = assignment.userItemId();
        if (userItemId == null) {
            // 빈 슬롯 지정 → 기존 배치가 있으면 제거.
            roomSurfaceSlotRepository.findByRoomUserIdAndSlotType(userId, slotType)
                    .ifPresent(roomSurfaceSlotRepository::delete);
            return;
        }

        UserItem userItem = ownedItems.get(userItemId);
        if (userItem == null) {
            throw new BusinessException(RoomErrorCode.ITEM_NOT_OWNED);
        }

        roomSurfaceSlotRepository.findByRoomUserIdAndSlotType(userId, slotType)
                .ifPresentOrElse(
                        slot -> slot.assign(userItem),
                        () -> roomSurfaceSlotRepository.save(
                                RoomSurfaceSlot.create(room, slotType, userItem)));
    }
}
