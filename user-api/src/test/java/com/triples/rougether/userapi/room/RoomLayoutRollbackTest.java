package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
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
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// surface + placements 단일 트랜잭션 정합성 - 뒤 단계(placements 소유 검증) 실패 시
// 앞 단계(surface 슬롯 저장)까지 전부 롤백되는지 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
@SpringBootTest
class RoomLayoutRollbackTest {

    @Autowired private RoomCommandService roomCommandService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    @Autowired private RoomItemPlacementRepository roomItemPlacementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;

    private Long userId;
    private Long themeId;
    private Long itemId;
    private Long userItemId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (userId != null) {
            roomItemPlacementRepository.deleteAll(roomItemPlacementRepository.findByRoomUserIdWithItem(userId));
            roomSurfaceSlotRepository.deleteAll(roomSurfaceSlotRepository.findByRoomUserId(userId));
            personalRoomRepository.deleteById(userId);
        }
        if (userItemId != null) {
            userItemRepository.deleteById(userItemId);
        }
        if (itemId != null) {
            itemRepository.deleteById(itemId);
        }
        if (themeId != null) {
            themeRepository.deleteById(themeId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    void placements_검증이_실패하면_surface_슬롯_변경까지_전부_롤백된다() {
        User me = userRepository.save(User.signUp("room-layout-rollback@rougether.dev"));
        userId = me.getId();
        Theme theme = themeRepository.save(new Theme("layout_rb_theme", "롤백 테마", "themes/rb.png", true));
        themeId = theme.getId();
        Item wallpaper = itemRepository.save(new Item(theme, "wallpaper", "surface_slot", "wallpaper", null,
                "롤백 벽지", CurrencyType.DIAMOND, 100, "items/rb/wall.png", false, true));
        itemId = wallpaper.getId();
        UserItem owned = userItemRepository.save(UserItem.create(me, wallpaper));
        userItemId = owned.getId();

        roomQueryService.getMyRoom(userId); // 방 선생성(커밋) - 롤백 대상을 layout 저장분으로 한정

        // surface 는 소유 아이템으로 정상, placements 에 미소유 id → 소유 검증은 surface 반영 뒤에 실패한다
        assertThatThrownBy(() -> roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0,
                List.of(new SurfaceSlotAssignment("wallpaper", owned.getId())),
                List.of(new PlacementItem(999999L, new BigDecimal("0.5"), new BigDecimal("0.5"),
                        0, null, null, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(RoomErrorCode.ITEM_NOT_OWNED));

        // 단일 트랜잭션이므로 surface 저장·전환·revision 증가 전부 롤백
        assertThat(roomSurfaceSlotRepository.findByRoomUserId(userId)).isEmpty();
        assertThat(roomItemPlacementRepository.findByRoomUserIdWithItem(userId)).isEmpty();
        PersonalRoom room = personalRoomRepository.findById(userId).orElseThrow();
        assertThat(room.isFreeLayout()).isFalse();
        assertThat(room.getLayoutRevision()).isZero();
    }
}
