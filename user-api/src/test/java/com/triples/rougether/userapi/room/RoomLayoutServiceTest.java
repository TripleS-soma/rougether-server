package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.PlacementItem;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.SurfaceSlotAssignment;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// PUT /rooms/me/layout(자유배치 저장)의 서비스 규칙 검증. DB 레벨(unique·flush 순서)은 통합 테스트에서 다룬다.
@ExtendWith(MockitoExtension.class)
class RoomLayoutServiceTest {

    @Mock private PersonalRoomRepository personalRoomRepository;
    @Mock private RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    @Mock private RoomItemPlacementRepository roomItemPlacementRepository;
    @Mock private UserItemRepository userItemRepository;
    @Mock private StreakRepository streakRepository;
    @Mock private UserCharacterRepository userCharacterRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private RoomCommandService roomCommandService;

    private static final Long USER_ID = 1L;

    private PersonalRoom realRoom() {
        return PersonalRoom.create(mock(User.class));
    }

    private UserItem ownedItem(Long id) {
        UserItem userItem = mock(UserItem.class);
        when(userItem.getId()).thenReturn(id);
        return userItem;
    }

    private PlacementItem placement(Long userItemId, String x, String y) {
        return new PlacementItem(userItemId, new BigDecimal(x), new BigDecimal(y), 0, null, null, null);
    }

    private void stubAssemble() {
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(USER_ID)).thenReturn(List.of());
        when(roomItemPlacementRepository.findByRoomUserIdWithItem(USER_ID)).thenReturn(List.of());
        when(streakRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(USER_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    void 최초_저장시_FREE_V1으로_전환되고_revision이_증가한다() {
        PersonalRoom room = realRoom();
        UserItem item = ownedItem(77L);
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(item));
        stubAssemble();

        roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(77L, "0.32", "0.68"))));

        assertThat(room.isFreeLayout()).isTrue();
        assertThat(room.getLayoutRevision()).isEqualTo(1);
        // 전체 교체: delete 가 saveAll 보다 먼저 실행돼야 unique 충돌이 없다.
        var order = inOrder(roomItemPlacementRepository);
        order.verify(roomItemPlacementRepository).deleteByRoomUserId(USER_ID);
        order.verify(roomItemPlacementRepository).saveAll(anyList());
    }

    @Test
    void baseRevision이_다르면_409_충돌로_거부한다() {
        PersonalRoom room = realRoom(); // revision 0
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                3, List.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.LAYOUT_REVISION_CONFLICT));
        verify(roomItemPlacementRepository, never()).deleteByRoomUserId(any());
        assertThat(room.isFreeLayout()).isFalse();
    }

    @Test
    void 방이_없으면_lazy_생성하고_baseRevision_0으로_저장한다() {
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        PersonalRoom created = realRoom();
        when(personalRoomRepository.save(any(PersonalRoom.class))).thenReturn(created);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of());
        stubAssemble();

        roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(0, List.of(), List.of()));

        verify(personalRoomRepository).save(any(PersonalRoom.class));
        assertThat(created.isFreeLayout()).isTrue();
        assertThat(created.getLayoutRevision()).isEqualTo(1);
    }

    @Test
    void 소유하지_않은_아이템_배치는_거부한다() {
        PersonalRoom room = realRoom();
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(99L, "0.5", "0.5")))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.ITEM_NOT_OWNED));
        // 소유 검증은 쓰기(delete·saveAll)보다 앞에서 실패해야 한다.
        verify(roomItemPlacementRepository, never()).deleteByRoomUserId(any());
        verify(roomItemPlacementRepository, never()).saveAll(anyList());
    }

    @Test
    void 같은_보유_아이템을_중복_배치하면_거부한다() {
        PersonalRoom room = realRoom();
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(77L, "0.1", "0.1"), placement(77L, "0.9", "0.9")))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.DUPLICATE_PLACEMENT_ITEM));
    }

    @Test
    void 좌표가_0과_1_범위를_벗어나면_거부한다() {
        PersonalRoom room = realRoom();
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(77L, "1.2", "0.5")))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.INVALID_PLACEMENT));
    }

    @Test
    void scale과_rotation이_허용_범위를_벗어나면_거부한다() {
        PersonalRoom room = realRoom();
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(new PlacementItem(
                        77L, new BigDecimal("0.5"), new BigDecimal("0.5"), 0, new BigDecimal("6.0"), null, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.INVALID_PLACEMENT));

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(new PlacementItem(
                        77L, new BigDecimal("0.5"), new BigDecimal("0.5"), 0, null, 400, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.INVALID_PLACEMENT));

        // Math.abs 오버플로 회귀 - Integer.MIN_VALUE 도 범위 밖으로 거부되어야 한다
        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(new PlacementItem(
                        77L, new BigDecimal("0.5"), new BigDecimal("0.5"), 0, null, Integer.MIN_VALUE, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.INVALID_PLACEMENT));
    }

    @Test
    void surfaceSlots에_positioned_슬롯타입이_오면_거부한다() {
        PersonalRoom room = realRoom();
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(new SurfaceSlotAssignment("topLeft", 10L)), List.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.INVALID_SLOT_TYPE));
    }

    @Test
    void 경계값_좌표0과1_scale최소최대_rotation360은_허용한다() {
        PersonalRoom room = realRoom();
        UserItem item77 = ownedItem(77L);
        UserItem item78 = ownedItem(78L);
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(item77, item78));
        stubAssemble();

        // 같은 좌표(겹침) + 경계값 전부 허용 - outOfRange 부호 회귀 방지
        roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(
                        new PlacementItem(77L, new BigDecimal("0.0"), new BigDecimal("1.0"),
                                0, new BigDecimal("0.1"), -360, false),
                        new PlacementItem(78L, new BigDecimal("0.0"), new BigDecimal("1.0"),
                                1, new BigDecimal("5.0"), 360, true))));

        verify(roomItemPlacementRepository).saveAll(anyList());
    }

    @Test
    void scale_rotation_flipped_생략시_기본값으로_저장한다() {
        PersonalRoom room = realRoom();
        UserItem item = ownedItem(77L);
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(item));
        stubAssemble();

        roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(77L, "0.32", "0.68"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoomItemPlacement>> captor = ArgumentCaptor.forClass(List.class);
        verify(roomItemPlacementRepository).saveAll(captor.capture());
        RoomItemPlacement saved = captor.getValue().get(0);
        assertThat(saved.getScale()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(saved.getRotationDeg()).isZero();
        assertThat(saved.isFlipped()).isFalse();
    }

    // DB 컬럼 정밀도로 정규화 저장 - 저장 직후 응답과 이후 조회(MySQL 반올림 값)가 일치해야 한다
    @Test
    void 좌표와_scale은_DB_정밀도로_반올림해_저장한다() {
        PersonalRoom room = realRoom();
        UserItem item = ownedItem(77L);
        when(personalRoomRepository.findWithLockById(USER_ID)).thenReturn(Optional.of(room));
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(item));
        stubAssemble();

        roomCommandService.updateLayout(USER_ID, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(new PlacementItem(
                        77L, new BigDecimal("0.1234567"), new BigDecimal("0.9999999"),
                        0, new BigDecimal("1.005"), null, null))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoomItemPlacement>> captor = ArgumentCaptor.forClass(List.class);
        verify(roomItemPlacementRepository).saveAll(captor.capture());
        RoomItemPlacement saved = captor.getValue().get(0);
        assertThat(saved.getPositionX()).isEqualByComparingTo(new BigDecimal("0.12346"));
        assertThat(saved.getPositionY()).isEqualByComparingTo(new BigDecimal("1.00000"));
        assertThat(saved.getScale()).isEqualByComparingTo(new BigDecimal("1.01"));
    }
}
