package com.triples.rougether.userapi.room.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomSlotType;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.PlacementItem;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.SurfaceSlotAssignment;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 방 배치 저장. 슬롯 방식(updateSlots)과 자유배치(updateLayout)를 함께 제공한다.
// 방이 없으면 조회와 동일하게 첫 방문으로 보고 lazy 생성한다.
@Service
public class RoomCommandService {

    // 자유배치 값 허용 범위 (팀 확정 #162). 겹침·충돌 검증은 하지 않는다.
    private static final BigDecimal POSITION_MIN = BigDecimal.ZERO;
    private static final BigDecimal POSITION_MAX = BigDecimal.ONE;
    private static final BigDecimal SCALE_MIN = new BigDecimal("0.1");
    private static final BigDecimal SCALE_MAX = new BigDecimal("5.0");
    private static final BigDecimal SCALE_DEFAULT = BigDecimal.ONE;
    private static final int ROTATION_LIMIT_DEG = 360;

    private final PersonalRoomRepository personalRoomRepository;
    private final RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    private final RoomItemPlacementRepository roomItemPlacementRepository;
    private final UserItemRepository userItemRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final StreakRepository streakRepository;
    private final UserRepository userRepository;

    public RoomCommandService(PersonalRoomRepository personalRoomRepository,
                              RoomSurfaceSlotRepository roomSurfaceSlotRepository,
                              RoomItemPlacementRepository roomItemPlacementRepository,
                              UserItemRepository userItemRepository,
                              UserCharacterRepository userCharacterRepository,
                              StreakRepository streakRepository,
                              UserRepository userRepository) {
        this.personalRoomRepository = personalRoomRepository;
        this.roomSurfaceSlotRepository = roomSurfaceSlotRepository;
        this.roomItemPlacementRepository = roomItemPlacementRepository;
        this.userItemRepository = userItemRepository;
        this.userCharacterRepository = userCharacterRepository;
        this.streakRepository = streakRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RoomResponse updateSlots(Long userId, RoomSlotUpdateRequest request) {
        validateNoDuplicateSlotType(request);

        // updateLayout 과 같은 방 행 락 - 락 없이 읽으면 동시 FREE_V1 전환을 통과한 슬롯 저장이
        // 정본에 반영되지 않는 positioned row 를 남길 수 있다(성공했는데 화면에 없는 유실).
        PersonalRoom room = personalRoomRepository.findWithLockById(userId)
                .orElseGet(() -> personalRoomRepository.save(
                        PersonalRoom.create(userRepository.getReferenceById(userId))));

        // FREE_V1 전환 방: 슬롯 모델은 자유배치(한 영역 다중 가구)를 표현할 수 없어, positioned 가 포함된
        // 구버전 저장은 자유배치 데이터 보존을 위해 거부한다. surface(벽지/바닥/배경)만이면 기존대로 허용(팀 확정).
        if (room.isFreeLayout() && hasPositionedSlot(request)) {
            throw new BusinessException(RoomErrorCode.LAYOUT_FORMAT_CONFLICT);
        }

        // 소유 아이템 맵(id -> UserItem). 배치 대상 소유 검증 + 참조 확보에 재사용.
        Map<Long, UserItem> ownedItems = ownedItemsOf(userId);

        for (RoomSlotUpdateRequest.SlotAssignment assignment : request.slots()) {
            applyAssignment(userId, room, ownedItems, assignment.slotType(), assignment.userItemId());
        }

        // 슬롯 저장도 revision 을 올려 다른 기기(새 앱)가 stale baseRevision 으로 덮어쓰지 못하게 한다.
        if (!request.slots().isEmpty()) {
            room.increaseLayoutRevision();
        }

        return assemble(userId, room);
    }

    // 자유배치 저장: surface 슬롯 + placements 전체 교체를 단일 트랜잭션으로.
    // 같은 방의 동시 저장은 행 락으로 직렬화하고, baseRevision 불일치(다른 기기가 먼저 저장)는 409 로 거부한다.
    @Transactional
    public RoomResponse updateLayout(Long userId, RoomLayoutUpdateRequest request) {
        PersonalRoom room = personalRoomRepository.findWithLockById(userId)
                .orElseGet(() -> personalRoomRepository.save(
                        PersonalRoom.create(userRepository.getReferenceById(userId))));

        if (request.baseRevision() != room.getLayoutRevision()) {
            throw new BusinessException(RoomErrorCode.LAYOUT_REVISION_CONFLICT);
        }

        validateSurfaceSlots(request.surfaceSlots());
        validatePlacements(request.placements());

        Map<Long, UserItem> ownedItems = ownedItemsOf(userId);
        // 소유 검증을 쓰기(surface upsert·delete)보다 앞에 둬 실패 시 불필요한 delete+rollback 을 피한다.
        for (PlacementItem item : request.placements()) {
            if (!ownedItems.containsKey(item.userItemId())) {
                throw new BusinessException(RoomErrorCode.ITEM_NOT_OWNED);
            }
        }

        for (SurfaceSlotAssignment assignment : request.surfaceSlots()) {
            applyAssignment(userId, room, ownedItems, assignment.slotType(), assignment.userItemId());
        }

        // 전체 교체. bulk delete(flushAutomatically)가 insert 보다 먼저 DB 에 반영돼
        // 같은 (room, userItem) 재배치도 UNIQUE(room_user_id, user_item_id) 충돌 없이 통과한다.
        roomItemPlacementRepository.deleteByRoomUserId(userId);
        List<RoomItemPlacement> placements = new ArrayList<>();
        for (PlacementItem item : request.placements()) {
            placements.add(RoomItemPlacement.place(
                    room, ownedItems.get(item.userItemId()), item.positionX(), item.positionY(), item.zIndex(),
                    scaleOrDefault(item), rotationOrDefault(item), Boolean.TRUE.equals(item.flipped())));
        }
        roomItemPlacementRepository.saveAll(placements);

        // 첫 자유배치 저장 시 그 방만 지연 전환. 기존 positioned 슬롯 row 는 구버전 표시 fallback 으로 남긴다.
        room.convertToFreeLayout();
        room.increaseLayoutRevision();

        return assemble(userId, room);
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

    private boolean hasPositionedSlot(RoomSlotUpdateRequest request) {
        return request.slots().stream()
                .anyMatch(assignment -> RoomSlotType.isPositionedCode(assignment.slotType()));
    }

    // layout 의 surfaceSlots 는 surface 3종만 허용(가구는 placements 로만). 중복 slotType 도 슬롯 저장과 동일하게 거부.
    private void validateSurfaceSlots(List<SurfaceSlotAssignment> surfaceSlots) {
        for (SurfaceSlotAssignment assignment : surfaceSlots) {
            if (!RoomSlotType.isSurfaceCode(assignment.slotType())) {
                throw new BusinessException(RoomErrorCode.INVALID_SLOT_TYPE);
            }
        }
        long distinct = surfaceSlots.stream()
                .map(SurfaceSlotAssignment::slotType)
                .distinct()
                .count();
        if (distinct != surfaceSlots.size()) {
            throw new BusinessException(RoomErrorCode.DUPLICATE_SLOT_TYPE);
        }
    }

    // 같은 userItemId 중복(사본 1개를 두 곳에 배치) 거부 + 좌표·배율·회전 허용 범위 검증. 겹침 검증은 하지 않는다.
    private void validatePlacements(List<PlacementItem> placements) {
        long distinct = placements.stream()
                .map(PlacementItem::userItemId)
                .distinct()
                .count();
        if (distinct != placements.size()) {
            throw new BusinessException(RoomErrorCode.DUPLICATE_PLACEMENT_ITEM);
        }
        for (PlacementItem item : placements) {
            int rotation = rotationOrDefault(item);
            // Math.abs 는 Integer.MIN_VALUE 에서 오버플로로 음수를 반환해 검증을 통과시키므로 직접 비교한다.
            if (outOfRange(item.positionX(), POSITION_MIN, POSITION_MAX)
                    || outOfRange(item.positionY(), POSITION_MIN, POSITION_MAX)
                    || outOfRange(scaleOrDefault(item), SCALE_MIN, SCALE_MAX)
                    || rotation < -ROTATION_LIMIT_DEG || rotation > ROTATION_LIMIT_DEG) {
                throw new BusinessException(RoomErrorCode.INVALID_PLACEMENT);
            }
        }
    }

    private boolean outOfRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }

    private BigDecimal scaleOrDefault(PlacementItem item) {
        return item.scale() != null ? item.scale() : SCALE_DEFAULT;
    }

    private int rotationOrDefault(PlacementItem item) {
        return item.rotationDeg() != null ? item.rotationDeg() : 0;
    }

    private Map<Long, UserItem> ownedItemsOf(Long userId) {
        return userItemRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .collect(Collectors.toMap(UserItem::getId, Function.identity()));
    }

    private void applyAssignment(Long userId, PersonalRoom room, Map<Long, UserItem> ownedItems,
                                 String slotType, Long userItemId) {
        if (!RoomSlotType.isValid(slotType)) {
            throw new BusinessException(RoomErrorCode.INVALID_SLOT_TYPE);
        }

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

    // 저장 후의 방 전체 상태(내 방 조회와 동일 형식). saveAll 직후의 조회는 auto flush 로 신규 배치를 포함한다.
    private RoomResponse assemble(Long userId, PersonalRoom room) {
        List<RoomSurfaceSlot> slots = roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId);
        List<RoomItemPlacement> placements = roomItemPlacementRepository.findByRoomUserIdWithItem(userId);
        Streak streak = streakRepository.findByUserId(userId).orElse(null);
        UserCharacter selectedCharacter = userCharacterRepository
                .findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId).orElse(null);
        return RoomResponse.of(room, slots, placements, streak, selectedCharacter);
    }
}
