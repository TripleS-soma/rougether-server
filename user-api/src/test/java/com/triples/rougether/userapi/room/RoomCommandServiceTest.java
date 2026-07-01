package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest.SlotAssignment;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomCommandServiceTest {

    @Mock private PersonalRoomRepository personalRoomRepository;
    @Mock private RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    @Mock private UserItemRepository userItemRepository;
    @Mock private StreakRepository streakRepository;
    @Mock private UserCharacterRepository userCharacterRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private RoomCommandService roomCommandService;

    // RoomResponse.of 가 읽는 필드까지 stub 한 방 mock(성공 경로용).
    private PersonalRoom stubbedRoom(Long userId) {
        PersonalRoom room = mock(PersonalRoom.class);
        when(room.getUserId()).thenReturn(userId);
        when(room.getGrowthLevel()).thenReturn(0);
        when(room.getUpdatedAt()).thenReturn(Instant.EPOCH);
        return room;
    }

    private UserItem ownedItem(Long id) {
        UserItem userItem = mock(UserItem.class);
        when(userItem.getId()).thenReturn(id);
        return userItem;
    }

    @Test
    void 소유한_아이템을_빈_슬롯에_배치하면_저장한다() {
        Long userId = 1L;
        PersonalRoom room = stubbedRoom(userId);
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(room));
        UserItem item = ownedItem(10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of(item));
        when(roomSurfaceSlotRepository.findByRoomUserIdAndSlotType(userId, "topLeft")).thenReturn(Optional.empty());
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId)).thenReturn(List.of());
        when(streakRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(List.of(new SlotAssignment("topLeft", 10L))));

        verify(roomSurfaceSlotRepository).save(any(RoomSurfaceSlot.class));
    }

    @Test
    void 이미_배치된_슬롯이면_새로_저장하지_않고_교체한다() {
        Long userId = 1L;
        PersonalRoom room = stubbedRoom(userId);
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(room));
        UserItem item = ownedItem(20L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of(item));
        RoomSurfaceSlot existing = mock(RoomSurfaceSlot.class);
        when(roomSurfaceSlotRepository.findByRoomUserIdAndSlotType(userId, "floor")).thenReturn(Optional.of(existing));
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId)).thenReturn(List.of());
        when(streakRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(List.of(new SlotAssignment("floor", 20L))));

        verify(existing).assign(item);
        verify(roomSurfaceSlotRepository, never()).save(any());
    }

    @Test
    void 소유하지_않은_아이템_배치는_거부한다() {
        Long userId = 1L;
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(mock(PersonalRoom.class)));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of()); // 소유 없음

        assertThatThrownBy(() -> roomCommandService.updateSlots(
                userId, new RoomSlotUpdateRequest(List.of(new SlotAssignment("topLeft", 99L)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(RoomErrorCode.ITEM_NOT_OWNED));
        verify(roomSurfaceSlotRepository, never()).save(any());
    }

    @Test
    void 정의되지_않은_슬롯타입은_거부한다() {
        Long userId = 1L;
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(mock(PersonalRoom.class)));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> roomCommandService.updateSlots(
                userId, new RoomSlotUpdateRequest(List.of(new SlotAssignment("topCenter", 1L)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(RoomErrorCode.INVALID_SLOT_TYPE));
    }

    @Test
    void 한_요청에_같은_슬롯타입이_중복이면_거부한다() {
        Long userId = 1L;

        assertThatThrownBy(() -> roomCommandService.updateSlots(
                userId, new RoomSlotUpdateRequest(List.of(
                        new SlotAssignment("floor", 10L),
                        new SlotAssignment("floor", 20L)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(RoomErrorCode.DUPLICATE_SLOT_TYPE));
        verify(roomSurfaceSlotRepository, never()).save(any());
    }

    @Test
    void userItemId가_null이면_해당_슬롯을_비운다() {
        Long userId = 1L;
        PersonalRoom room = stubbedRoom(userId);
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(room));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(List.of());
        RoomSurfaceSlot existing = mock(RoomSurfaceSlot.class);
        when(roomSurfaceSlotRepository.findByRoomUserIdAndSlotType(userId, "topLeft")).thenReturn(Optional.of(existing));
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId)).thenReturn(List.of());
        when(streakRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(List.of(new SlotAssignment("topLeft", null))));

        verify(roomSurfaceSlotRepository).delete(existing);
        verify(roomSurfaceSlotRepository, never()).save(any());
    }
}
