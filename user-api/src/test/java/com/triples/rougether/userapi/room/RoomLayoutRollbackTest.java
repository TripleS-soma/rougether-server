package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

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
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// surface + placements 단일 트랜잭션 정합성 - 뒤 단계(placements insert)가 실패하면
// 앞 단계(surface 슬롯 저장·bulk delete)까지 전부 롤백되는지 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
@SpringBootTest
class RoomLayoutRollbackTest {

    @Autowired private RoomCommandService roomCommandService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;

    @MockitoSpyBean
    private RoomItemPlacementRepository roomItemPlacementRepository;

    private Long userId;
    private Long themeId;
    private Long wallpaperItemId;
    private Long chairItemId;
    private Long wallpaperUserItemId;
    private Long chairUserItemId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (userId != null) {
            roomItemPlacementRepository.deleteAll(roomItemPlacementRepository.findByRoomUserIdWithItem(userId));
            roomSurfaceSlotRepository.deleteAll(roomSurfaceSlotRepository.findByRoomUserId(userId));
            personalRoomRepository.deleteById(userId);
        }
        if (wallpaperUserItemId != null) {
            userItemRepository.deleteById(wallpaperUserItemId);
        }
        if (chairUserItemId != null) {
            userItemRepository.deleteById(chairUserItemId);
        }
        if (wallpaperItemId != null) {
            itemRepository.deleteById(wallpaperItemId);
        }
        if (chairItemId != null) {
            itemRepository.deleteById(chairItemId);
        }
        if (themeId != null) {
            themeRepository.deleteById(themeId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    void placements_저장이_실패하면_surface_슬롯_변경까지_전부_롤백된다() {
        User me = userRepository.save(User.signUp("room-layout-rollback@rougether.dev"));
        userId = me.getId();
        Theme theme = themeRepository.save(new Theme("layout_rb_theme", "롤백 테마", "themes/rb.png", true));
        themeId = theme.getId();
        Item wallpaper = itemRepository.save(new Item(theme, "wallpaper", "surface_slot", "wallpaper", null,
                "롤백 벽지", CurrencyType.DIAMOND, 100, "items/rb/wall.png", false, true));
        wallpaperItemId = wallpaper.getId();
        Item chair = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "롤백 의자", CurrencyType.DIAMOND, 100, "items/rb/chair.png", false, true));
        chairItemId = chair.getId();
        UserItem ownedWallpaper = userItemRepository.save(UserItem.create(me, wallpaper));
        wallpaperUserItemId = ownedWallpaper.getId();
        UserItem ownedChair = userItemRepository.save(UserItem.create(me, chair));
        chairUserItemId = ownedChair.getId();

        roomQueryService.getMyRoom(userId); // 방 선생성(커밋) - 롤백 대상을 layout 저장분으로 한정

        // 검증·소유 확인·surface upsert·bulk delete 를 모두 통과한 뒤 insert 단계에서 터뜨림
        doThrow(new RuntimeException("placements 저장 실패")).when(roomItemPlacementRepository).saveAll(anyList());

        assertThatThrownBy(() -> roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0,
                List.of(new SurfaceSlotAssignment("wallpaper", ownedWallpaper.getId())),
                List.of(new PlacementItem(ownedChair.getId(), new BigDecimal("0.5"), new BigDecimal("0.5"),
                        0, null, null, null)))))
                .isInstanceOf(RuntimeException.class);

        // 단일 트랜잭션이므로 surface 저장·전환·revision 증가 전부 롤백
        assertThat(roomSurfaceSlotRepository.findByRoomUserId(userId)).isEmpty();
        assertThat(roomItemPlacementRepository.findByRoomUserIdWithItem(userId)).isEmpty();
        PersonalRoom room = personalRoomRepository.findById(userId).orElseThrow();
        assertThat(room.isFreeLayout()).isFalse();
        assertThat(room.getLayoutRevision()).isZero();
    }
}
