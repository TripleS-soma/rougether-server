package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.house.dto.HousePreviewDetailResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewDetailResponse.MemberRoomSummary;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.PlacementItem;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.SurfaceSlotAssignment;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 비구성원 집 미리보기(#169) - 전체공개 조회·isMember/isFull 판정·KICKED 허용·삭제 404 를 실제 DB 로 검증.
@SpringBootTest
@Transactional
class HousePreviewQueryTest {

    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseMemberCommandService houseMemberCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseGoalRepository houseGoalRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RoomCommandService roomCommandService;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;

    private User owner;
    private User stranger;
    private House house;
    private HouseMember ownerMembership;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.signUp("preview-owner@rougether.dev"));
        stranger = userRepository.save(User.signUp("preview-stranger@rougether.dev"));
        house = houseRepository.save(House.create(
                owner, "미리보기 하우스", "같이 아침 루틴 지켜요", "house/preview.png",
                2, "PREVIEW2", Instant.now().plus(Duration.ofDays(7))));
        ownerMembership = houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));

        // goals 는 마스터 테이블(엔티티에 생성자 없음) - 테스트 픽스처는 SQL 로 적재.
        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES ('preview_goal', '미리보기 목표', 1, true)");
        Goal goal = goalRepository.findByIdInAndActiveIsTrue(
                jdbcTemplate.queryForList("SELECT id FROM goals WHERE code = 'preview_goal'", Long.class)).get(0);
        houseGoalRepository.save(HouseGoal.create(house, goal));
    }

    @Test
    void 비구성원도_상세와_동일한_집_정보를_조회한다() {
        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(response.houseId()).isEqualTo(house.getId());
        assertThat(response.name()).isEqualTo("미리보기 하우스");
        assertThat(response.description()).isEqualTo("같이 아침 루틴 지켜요");
        assertThat(response.coverImageKey()).isEqualTo("house/preview.png");
        assertThat(response.currentMemberCount()).isEqualTo(1);
        assertThat(response.maxMembers()).isEqualTo(2);
        assertThat(response.level()).isZero();
        assertThat(response.goals()).hasSize(1);
        assertThat(response.goals().get(0).code()).isEqualTo("preview_goal");
        assertThat(response.isMember()).isFalse();
        assertThat(response.isFull()).isFalse();
    }

    @Test
    void 활성_구성원이면_isMember가_true다() {
        HousePreviewDetailResponse response = houseQueryService.getPreview(owner.getId(), house.getId());

        assertThat(response.isMember()).isTrue();
    }

    @Test
    void 정원이_차면_isFull이_true다() {
        HouseMember joined = houseMemberRepository.save(
                HouseMember.create(house, stranger, HouseMemberRole.MEMBER));
        house.increaseMemberCount(); // 참여 확정과 동일 규칙으로 카운트 반영 (max 2 도달)

        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(joined.isActive()).isTrue();
        assertThat(response.isFull()).isTrue();
        assertThat(response.isMember()).isTrue();
    }

    @Test
    void KICKED_이력자도_미리보기는_조회할_수_있고_isMember는_false다() {
        HouseMember member = houseMemberRepository.save(
                HouseMember.create(house, stranger, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseMemberCommandService.kick(owner.getId(), house.getId(), member.getId());

        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(response.isMember()).isFalse();
        assertThat(response.currentMemberCount()).isEqualTo(1);
    }

    // owner 방을 실제 저장 경로(자유배치 API)로 꾸민다 - 벽지 1 + 가구 1 + 캐릭터 착용.
    private void decorateOwnerRoom() {
        Theme theme = themeRepository.save(new Theme("preview_theme", "미리보기 테마", "themes/preview.png", true));
        Item chair = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                "미리보기 의자", CurrencyType.DIAMOND, 100, "items/preview/chair.png", false, true));
        Item wallpaper = itemRepository.save(new Item(theme, "wallpaper", "surface_slot", "wallpaper", null,
                "미리보기 벽지", CurrencyType.DIAMOND, 100, "items/preview/wall.png", false, true));
        UserItem chairOwned = userItemRepository.save(UserItem.create(owner, chair));
        UserItem wallpaperOwned = userItemRepository.save(UserItem.create(owner, wallpaper));
        roomCommandService.updateLayout(owner.getId(), new RoomLayoutUpdateRequest(
                0,
                List.of(new SurfaceSlotAssignment("wallpaper", wallpaperOwned.getId())),
                List.of(new PlacementItem(chairOwned.getId(), new BigDecimal("0.32"), new BigDecimal("0.68"),
                        3, new BigDecimal("1.1"), 15, false))));
        Character cat = characterRepository.save(new Character("preview_cat", "고양이", "characters/cat.png", 1, true));
        userCharacterRepository.save(UserCharacter.createSelected(owner, cat));
    }

    @Test
    void 비구성원에게_구성원_방_렌더_데이터가_가입순으로_내려간다() {
        decorateOwnerRoom();
        HouseMember second = houseMemberRepository.save(
                HouseMember.create(house, stranger, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        User outsider = userRepository.save(User.signUp("preview-outsider@rougether.dev"));

        HousePreviewDetailResponse response = houseQueryService.getPreview(outsider.getId(), house.getId());

        assertThat(response.memberRooms()).hasSize(2);
        // 가입순 - 생성자(owner)가 첫 번째
        MemberRoomSummary first = response.memberRooms().get(0);
        assertThat(first.membershipId()).isEqualTo(ownerMembership.getId());
        assertThat(first.nickname()).isNull(); // 온보딩 전
        assertThat(first.room()).isNotNull();
        assertThat(first.room().layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        assertThat(first.room().placements()).singleElement()
                .satisfies(placement -> assertThat(placement.assetKey()).isEqualTo("items/preview/chair.png"));
        assertThat(first.room().slots()).singleElement()
                .satisfies(slot -> {
                    assertThat(slot.slotType()).isEqualTo("wallpaper");
                    assertThat(slot.assetKey()).isEqualTo("items/preview/wall.png");
                });
        assertThat(first.room().character().code()).isEqualTo("preview_cat");
        // 방 미생성 구성원은 room null - 프론트는 기본 방 타일로 표시
        MemberRoomSummary noRoom = response.memberRooms().get(1);
        assertThat(noRoom.membershipId()).isEqualTo(second.getId());
        assertThat(noRoom.room()).isNull();
    }

    @Test
    void 미리보기_조회가_타인_방을_lazy_생성하지_않는다() {
        houseQueryService.getPreview(stranger.getId(), house.getId());

        // 내 방 조회(getMyRoom)와 달리 미리보기는 읽기 전용 - 방 없는 owner 의 방이 생기면 안 된다
        assertThat(personalRoomRepository.findById(owner.getId())).isEmpty();
    }

    @Test
    void 강퇴된_구성원의_방은_memberRooms에서_제외된다() {
        HouseMember member = houseMemberRepository.save(
                HouseMember.create(house, stranger, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseMemberCommandService.kick(owner.getId(), house.getId(), member.getId());

        HousePreviewDetailResponse response = houseQueryService.getPreview(stranger.getId(), house.getId());

        assertThat(response.memberRooms()).hasSize(1);
        assertThat(response.memberRooms().get(0).membershipId()).isEqualTo(ownerMembership.getId());
    }

    @Test
    void 삭제된_집은_404다() {
        house.softDelete();

        assertThatThrownBy(() -> houseQueryService.getPreview(stranger.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 기존_상세_API의_구성원_전용_계약은_변하지_않는다() {
        assertThatThrownBy(() -> houseQueryService.getHouseDetail(stranger.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }
}
