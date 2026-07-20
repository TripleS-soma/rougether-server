package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.PlacementItem;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.SurfaceSlotAssignment;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest.SlotAssignment;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 자유배치 저장·전환·구버전 호환 회귀 - 실제 DB 로 unique 제약(delete→insert flush 순서)까지 검증.
@SpringBootTest
@Transactional
class RoomLayoutIntegrationTest {

    @Autowired private RoomCommandService roomCommandService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private RoomItemPlacementRepository roomItemPlacementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;

    private Long userId;
    private UserItem chairOwned;
    private UserItem tableOwned;
    private UserItem wallpaperOwned;

    @BeforeEach
    void setUp() {
        User me = userRepository.save(User.signUp("room-layout-test@rougether.dev"));
        userId = me.getId();
        Theme theme = themeRepository.save(new Theme("layout_test_theme", "자유배치 테마", "themes/layout.png", true));
        // user_items 는 UNIQUE(user_id, item_id)(V8) - 같은 가구를 두 개 소유할 수 없어 서로 다른 아이템 2종으로 다중 배치를 검증한다.
        Item chair = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "자유배치 의자", CurrencyType.DIAMOND, 100, "items/layout/chair.png", false, true));
        Item table = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "자유배치 테이블", CurrencyType.DIAMOND, 100, "items/layout/table.png", false, true));
        Item wallpaper = itemRepository.save(new Item(theme, "wallpaper", "surface_slot", "wallpaper", null,
                "자유배치 벽지", CurrencyType.DIAMOND, 100, "items/layout/wall.png", false, true));
        chairOwned = userItemRepository.save(UserItem.create(me, chair));
        tableOwned = userItemRepository.save(UserItem.create(me, table));
        wallpaperOwned = userItemRepository.save(UserItem.create(me, wallpaper));
    }

    private PlacementItem placement(Long userItemId, String x, String y, int zIndex) {
        return new PlacementItem(userItemId, new BigDecimal(x), new BigDecimal(y), zIndex,
                new BigDecimal("1.1"), 15, true);
    }

    @Test
    void 최초_자유배치_저장시_FREE_V1으로_전환되고_응답에_placements가_내려간다() {
        RoomResponse response = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0,
                List.of(new SurfaceSlotAssignment("wallpaper", wallpaperOwned.getId())),
                List.of(placement(chairOwned.getId(), "0.32", "0.68", 3),
                        placement(tableOwned.getId(), "0.50", "0.20", 1))));

        assertThat(response.layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        assertThat(response.layoutRevision()).isEqualTo(1);
        // placements 는 zIndex 오름차순, surface 슬롯은 slots 로 유지
        assertThat(response.placements()).hasSize(2);
        assertThat(response.placements().get(0).userItemId()).isEqualTo(tableOwned.getId());
        assertThat(response.placements().get(1).userItemId()).isEqualTo(chairOwned.getId());
        assertThat(response.placements().get(1).positionX()).isEqualByComparingTo(new BigDecimal("0.32"));
        assertThat(response.placements().get(1).scale()).isEqualByComparingTo(new BigDecimal("1.1"));
        assertThat(response.placements().get(1).rotationDeg()).isEqualTo(15);
        assertThat(response.placements().get(1).flipped()).isTrue();
        assertThat(response.slots()).hasSize(1);
        assertThat(response.slots().get(0).slotType()).isEqualTo("wallpaper");

        PersonalRoom room = personalRoomRepository.findById(userId).orElseThrow();
        assertThat(room.isFreeLayout()).isTrue();
        assertThat(room.getLayoutRevision()).isEqualTo(1);
    }

    @Test
    void 같은_아이템_재배치는_unique_충돌_없이_전체_교체된다() {
        roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(chairOwned.getId(), "0.10", "0.10", 0))));

        // 같은 (room, userItem) 조합을 다시 보냄 - delete 가 insert 보다 먼저 flush 되어야 통과한다.
        RoomResponse response = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                1, List.of(), List.of(placement(chairOwned.getId(), "0.90", "0.90", 5))));

        assertThat(response.layoutRevision()).isEqualTo(2);
        List<RoomItemPlacement> placements = roomItemPlacementRepository.findByRoomUserIdWithItem(userId);
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).getPositionX()).isEqualByComparingTo(new BigDecimal("0.90"));
        assertThat(placements.get(0).getZIndex()).isEqualTo(5);
    }

    @Test
    void baseRevision이_다르면_409_충돌로_거부한다() {
        roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(chairOwned.getId(), "0.10", "0.10", 0))));

        assertThatThrownBy(() -> roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(chairOwned.getId(), "0.20", "0.20", 0)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.LAYOUT_REVISION_CONFLICT));
    }

    @Test
    void FREE_V1_방에_positioned_포함_슬롯_저장은_409이고_surface만은_허용된다() {
        roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(placement(chairOwned.getId(), "0.10", "0.10", 0))));

        assertThatThrownBy(() -> roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(
                List.of(new SlotAssignment("topLeft", tableOwned.getId())))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.LAYOUT_FORMAT_CONFLICT));

        // surface(벽지/바닥/배경)만 있는 구버전 저장은 허용(팀 확정) - 자유배치 데이터는 그대로 유지
        RoomResponse response = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(
                List.of(new SlotAssignment("wallpaper", wallpaperOwned.getId()))));
        assertThat(response.slots()).hasSize(1);
        assertThat(response.placements()).hasSize(1);
    }

    @Test
    void 전환_전_positioned_슬롯_row는_전환_후에도_유지되어_slots로_함께_내려간다() {
        // SLOT_V1 상태에서 positioned 슬롯을 채운 뒤 자유배치로 전환 - 구버전 표시 fallback 계약 검증
        RoomResponse afterSlots = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(
                List.of(new SlotAssignment("topLeft", chairOwned.getId()))));

        // 슬롯 저장도 revision 을 올리므로 전환은 최신 revision 을 baseRevision 으로 보낸다
        RoomResponse response = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                afterSlots.layoutRevision(), List.of(), List.of(placement(tableOwned.getId(), "0.40", "0.40", 0))));

        assertThat(response.layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        // 정본은 placements 지만 기존 positioned 슬롯 row 는 삭제되지 않고 slots 로 계속 노출된다
        assertThat(response.slots()).hasSize(1);
        assertThat(response.slots().get(0).slotType()).isEqualTo("topLeft");
        assertThat(response.placements()).hasSize(1);
    }

    @Test
    void 경계값과_동일_좌표_겹침_배치를_허용한다() {
        // 좌표 0.0/1.0, scale 0.1/5.0, rotation ±360 은 전부 허용 경계값이고, 겹침 검증은 하지 않는다(팀 확정)
        RoomResponse response = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0, List.of(), List.of(
                        new PlacementItem(chairOwned.getId(), new BigDecimal("0.0"), new BigDecimal("1.0"),
                                0, new BigDecimal("0.1"), -360, false),
                        new PlacementItem(tableOwned.getId(), new BigDecimal("0.0"), new BigDecimal("1.0"),
                                1, new BigDecimal("5.0"), 360, true))));

        assertThat(response.placements()).hasSize(2);
        assertThat(response.placements().get(0).positionY()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(response.placements().get(1).positionY()).isEqualByComparingTo(new BigDecimal("1.0"));
    }

    @Test
    void SLOT_V1_방의_기존_슬롯_저장과_조회는_동작이_변하지_않는다() {
        RoomResponse saved = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(
                List.of(new SlotAssignment("topLeft", chairOwned.getId()))));

        // positioned 저장이 그대로 되고, additive 필드가 함께 내려간다(구버전 앱은 무시).
        // 슬롯 저장도 revision 을 올린다 - 다른 기기의 stale baseRevision 전환을 막기 위함.
        assertThat(saved.slots()).hasSize(1);
        assertThat(saved.layoutFormat()).isEqualTo(RoomLayoutFormat.SLOT_V1);
        assertThat(saved.layoutRevision()).isEqualTo(1);
        assertThat(saved.placements()).isEmpty();

        RoomResponse queried = roomQueryService.getMyRoom(userId);
        assertThat(queried.slots()).hasSize(1);
        assertThat(queried.layoutFormat()).isEqualTo(RoomLayoutFormat.SLOT_V1);
        assertThat(queried.placements()).isEmpty();
    }
}
