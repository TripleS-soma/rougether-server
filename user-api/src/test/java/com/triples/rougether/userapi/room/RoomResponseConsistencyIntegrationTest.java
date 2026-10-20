package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.PlacementItem;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest.SurfaceSlotAssignment;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest.SlotAssignment;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 테스트 트랜잭션을 두지 않아 저장 응답과 커밋 후 새 영속성 컨텍스트의 조회 응답을 비교함.
@SpringBootTest
class RoomResponseConsistencyIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final Instant COBWEB_APPEARED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final String CHAIR_ASSET = "items/response-test/chair.png";
    private static final String TABLE_ASSET = "items/response-test/table.png";
    private static final String WALL_ASSET = "items/response-test/wall.png";
    private static final String GLASSES_ASSET = "items/response-test/glasses.png";
    private static final String OWNER_BIO = "매일 한 걸음씩 루틴을 쌓는 중";

    @Autowired private RoomCommandService roomCommandService;
    @Autowired private RoomQueryService roomQueryService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transaction;
    private MockedStatic<LocalDate> dates;
    private Long userId;
    private Long themeId;
    private Long characterId;
    private Long chairId;
    private Long tableId;
    private Long wallpaperId;
    private Long glassesId;
    private String characterCode;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(() -> LocalDate.now(KST)).thenReturn(TODAY);
        transaction.executeWithoutResult(status -> {
            User user = persist(User.signUp("room-response-" + UUID.randomUUID() + "@rougether.dev"));
            user.changeBio(OWNER_BIO);
            userId = user.getId();
        });
    }

    @AfterEach
    void cleanup() {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM room_cobwebs WHERE room_user_id = ?", userId);
                jdbc.update("DELETE FROM room_item_placements WHERE room_user_id = ?", userId);
                jdbc.update("DELETE FROM room_surface_slots WHERE room_user_id = ?", userId);
                jdbc.update("DELETE FROM streaks WHERE user_id = ?", userId);
                jdbc.update("""
                        DELETE FROM user_character_accessories
                        WHERE user_character_id IN (SELECT id FROM user_characters WHERE user_id = ?)
                        """, userId);
                jdbc.update("DELETE FROM user_characters WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM user_items WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM users WHERE id = ?", userId);
                if (characterId != null) {
                    jdbc.update("DELETE FROM character_accessory_render_profiles WHERE character_id = ?", characterId);
                    jdbc.update("DELETE FROM characters WHERE id = ?", characterId);
                }
                if (themeId != null) {
                    jdbc.update("DELETE FROM items WHERE theme_id = ?", themeId);
                    jdbc.update("DELETE FROM themes WHERE id = ?", themeId);
                }
            });
        } finally {
            if (dates != null) {
                dates.close();
            }
        }
    }

    @Test
    void 슬롯_저장_응답은_커밋_후_내방과_타인방_조회에서도_전체_상태가_유지된다() {
        createDecoratedRoom();

        RoomResponse saved = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(List.of(
                new SlotAssignment("wallpaper", wallpaperId),
                new SlotAssignment("topLeft", chairId))));

        assertDecoratedRoom(saved);
        assertThat(saved.layoutFormat()).isEqualTo(RoomLayoutFormat.SLOT_V1);
        assertThat(saved.layoutRevision()).isEqualTo(1);
        assertThat(saved.slots()).extracting("slotType", "userItemId", "assetKey")
                .containsExactlyInAnyOrder(
                        tuple("wallpaper", wallpaperId, WALL_ASSET),
                        tuple("topLeft", chairId, CHAIR_ASSET));
        assertThat(saved.placements()).isEmpty();
        assertCommittedResponses(saved);
    }

    @Test
    void 자유배치_저장_응답은_정규화와_정렬_기존슬롯까지_커밋_후_조회와_같다() {
        createDecoratedRoom();
        RoomResponse legacy = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(
                List.of(new SlotAssignment("topLeft", chairId))));

        RoomResponse saved = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                legacy.layoutRevision(),
                List.of(new SurfaceSlotAssignment("wallpaper", wallpaperId)),
                List.of(new PlacementItem(chairId, new BigDecimal("0.321236"), new BigDecimal("0.687654"),
                                3, new BigDecimal("1.126"), 15, true),
                        new PlacementItem(tableId, new BigDecimal("0.5"), new BigDecimal("0.2"),
                                1, null, null, null))));

        assertDecoratedRoom(saved);
        assertThat(saved.layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        assertThat(saved.layoutRevision()).isEqualTo(2);
        assertThat(saved.slots()).extracting("slotType", "userItemId", "assetKey")
                .containsExactlyInAnyOrder(
                        tuple("topLeft", chairId, CHAIR_ASSET),
                        tuple("wallpaper", wallpaperId, WALL_ASSET));
        assertThat(saved.placements()).extracting("userItemId", "assetKey", "zIndex", "rotationDeg", "flipped")
                .containsExactly(tuple(tableId, TABLE_ASSET, 1, 0, false), tuple(chairId, CHAIR_ASSET, 3, 15, true));
        var table = saved.placements().get(0);
        assertThat(table.positionX()).isEqualByComparingTo("0.50000");
        assertThat(table.positionY()).isEqualByComparingTo("0.20000");
        assertThat(table.scale()).isEqualByComparingTo("1.00");
        var chair = saved.placements().get(1);
        assertThat(chair.positionX()).isEqualByComparingTo("0.32124");
        assertThat(chair.positionY()).isEqualByComparingTo("0.68765");
        assertThat(chair.scale()).isEqualByComparingTo("1.13");
        assertCommittedResponses(saved);
    }

    @Test
    void 자유배치_방의_surface_슬롯을_비워도_가구배치는_커밋_후까지_유지된다() {
        createDecoratedRoom();
        RoomResponse layout = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(
                0, List.of(new SurfaceSlotAssignment("wallpaper", wallpaperId)),
                List.of(new PlacementItem(chairId, new BigDecimal("0.4"), new BigDecimal("0.6"),
                        2, BigDecimal.ONE, 0, false))));

        RoomResponse saved = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(
                List.of(new SlotAssignment("wallpaper", null))));

        assertDecoratedRoom(saved);
        assertThat(saved.layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        assertThat(saved.layoutRevision()).isEqualTo(layout.layoutRevision() + 1);
        assertThat(saved.slots()).isEmpty();
        assertThat(saved.placements()).singleElement().satisfies(placement -> {
            assertThat(placement.userItemId()).isEqualTo(chairId);
            assertThat(placement.positionX()).isEqualByComparingTo("0.40000");
            assertThat(placement.positionY()).isEqualByComparingTo("0.60000");
            assertThat(placement.zIndex()).isEqualTo(2);
        });
        assertCommittedResponses(saved);
    }

    @Test
    void 첫_빈방_슬롯저장과_빈_자유배치에서도_기본값과_null이_유지된다() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_rooms WHERE user_id = ?", Integer.class, userId))
                .isZero();

        RoomResponse slots = roomCommandService.updateSlots(userId, new RoomSlotUpdateRequest(List.of()));
        assertEmptyRoom(slots);
        assertThat(slots.layoutFormat()).isEqualTo(RoomLayoutFormat.SLOT_V1);
        assertThat(slots.layoutRevision()).isZero();
        assertCommittedResponses(slots);

        RoomResponse layout = roomCommandService.updateLayout(userId, new RoomLayoutUpdateRequest(0, List.of(), List.of()));
        assertEmptyRoom(layout);
        assertThat(layout.layoutFormat()).isEqualTo(RoomLayoutFormat.FREE_V1);
        assertThat(layout.layoutRevision()).isEqualTo(1);
        assertCommittedResponses(layout);
    }

    private void createDecoratedRoom() {
        transaction.executeWithoutResult(status -> {
            User user = entityManager.find(User.class, userId);
            PersonalRoom room = persist(PersonalRoom.create(user));
            room.changeGrowthPoints(290);
            room.changeGrowthPoints(-38);

            Theme theme = persist(new Theme("response_" + UUID.randomUUID(), "응답 검증 테마", null, true));
            themeId = theme.getId();
            chairId = own(user, new Item(theme, "furniture", "positioned", null, null,
                    "의자", CurrencyType.DIAMOND, 100, CHAIR_ASSET, false, true)).getId();
            tableId = own(user, new Item(theme, "furniture", "positioned", null, null,
                    "테이블", CurrencyType.DIAMOND, 100, TABLE_ASSET, false, true)).getId();
            wallpaperId = own(user, new Item(theme, "wallpaper", "surface_slot", "wallpaper", null,
                    "벽지", CurrencyType.DIAMOND, 100, WALL_ASSET, false, true)).getId();
            UserItem glasses = own(user, new Item(theme, "character_accessory", "character", null, "eyewear",
                    "안경", null, null, GLASSES_ASSET, false, true));
            glassesId = glasses.getId();

            characterCode = "response_" + UUID.randomUUID();
            Character character = persist(new Character(characterCode, "응답 검증 캐릭터", "characters/response-test.png", 10, true));
            characterId = character.getId();
            UserCharacter selected = persist(UserCharacter.createSelected(user, character));
            persist(UserCharacterAccessory.equip(selected, glasses, "eyewear"));
            persist(new CharacterAccessoryRenderProfile(glasses.getItem(), character, "default", GLASSES_ASSET,
                    180, 172, 320, 160, new BigDecimal("0.50000"), new BigDecimal("0.31000"),
                    new BigDecimal("0.5200"), -5, 20));

            Streak streak = persist(Streak.start(user, TODAY.minusDays(3)));
            streak.applySuccess(TODAY.minusDays(2));
            streak.applySuccess(TODAY.minusDays(1));
            entityManager.flush();
            // Hibernate의 Instant 매핑과 같은 UTC로 바인딩하여 JVM 기본 시간대의 영향을 없앰.
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO room_cobwebs (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                        VALUES (?, ?, NULL, NULL, ?)
                        """);
                statement.setLong(1, userId);
                statement.setTimestamp(2, Timestamp.from(COBWEB_APPEARED_AT), utcCalendar());
                statement.setTimestamp(3, Timestamp.from(COBWEB_APPEARED_AT), utcCalendar());
                return statement;
            });
        });
    }

    private UserItem own(User user, Item item) {
        return persist(UserItem.create(user, persist(item)));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    private void assertDecoratedRoom(RoomResponse response) {
        assertThat(response.roomUserId()).isEqualTo(userId);
        assertThat(response.ownerBio()).isEqualTo(OWNER_BIO);
        assertThat(response.growthLevel()).isEqualTo(9);
        assertThat(response.growthPoints()).isEqualTo(252);
        assertThat(response.pointsToNextLevel()).isEqualTo(38);
        // 현재 레벨이 10 미만이어도 최고 달성 레벨로 해금이 유지되는 경계를 검증함.
        assertThat(response.characterUnlocked()).isTrue();
        assertThat(response.characterUnlockLevel()).isEqualTo(10);
        assertThat(response.character().characterId()).isEqualTo(characterId);
        assertThat(response.character().code()).isEqualTo(characterCode);
        assertThat(response.character().name()).isEqualTo("응답 검증 캐릭터");
        assertThat(response.character().assetKey()).isEqualTo("characters/response-test.png");
        assertThat(response.character().animations().idle()).isEqualTo("characters/" + characterCode + "/animations/idle.webp");
        assertThat(response.character().animations().poseCycle())
                .isEqualTo("characters/" + characterCode + "/animations/pose-cycle.webp");
        assertThat(response.character().animations().wave())
                .isEqualTo("characters/" + characterCode + "/animations/wave.webp");
        assertThat(response.character().accessories()).singleElement().satisfies(accessory -> {
            assertThat(accessory.userItemId()).isEqualTo(glassesId);
            assertThat(accessory.name()).isEqualTo("안경");
            assertThat(accessory.assetKey()).isEqualTo(GLASSES_ASSET);
            assertThat(accessory.characterSlotType()).isEqualTo("eyewear");
            assertThat(accessory.renderProfiles()).singleElement().satisfies(profile -> {
                assertThat(profile.renderState()).isEqualTo("default");
                assertThat(profile.assetKey()).isEqualTo(GLASSES_ASSET);
                assertThat(profile.canvasWidth()).isEqualTo(180);
                assertThat(profile.canvasHeight()).isEqualTo(172);
                assertThat(profile.assetWidth()).isEqualTo(320);
                assertThat(profile.assetHeight()).isEqualTo(160);
                assertThat(profile.positionX()).isEqualByComparingTo("0.50000");
                assertThat(profile.positionY()).isEqualByComparingTo("0.31000");
                assertThat(profile.widthRatio()).isEqualByComparingTo("0.5200");
                assertThat(profile.rotationDeg()).isEqualTo(-5);
                assertThat(profile.zIndex()).isEqualTo(20);
            });
        });
        assertThat(response.streak()).isEqualTo(new RoomResponse.RoomStreakResponse(3, 3));
        assertThat(response.cobweb()).isEqualTo(new RoomResponse.RoomCobwebResponse(
                "items/common/decor/room-corner-cobweb.png", COBWEB_APPEARED_AT, true));
    }

    private void assertEmptyRoom(RoomResponse response) {
        assertThat(response.roomUserId()).isEqualTo(userId);
        assertThat(response.ownerBio()).isEqualTo(OWNER_BIO);
        assertThat(response.growthLevel()).isZero();
        assertThat(response.growthPoints()).isZero();
        assertThat(response.pointsToNextLevel()).isEqualTo(20);
        assertThat(response.characterUnlocked()).isFalse();
        assertThat(response.characterUnlockLevel()).isEqualTo(10);
        assertThat(response.character()).isNull();
        assertThat(response.slots()).isEmpty();
        assertThat(response.placements()).isEmpty();
        assertThat(response.streak()).isEqualTo(new RoomResponse.RoomStreakResponse(0, 0));
        assertThat(response.cobweb()).isNull();
    }

    private void assertCommittedResponses(RoomResponse saved) {
        RoomResponse mine = roomQueryService.getMyRoom(userId);
        RoomResponse other = roomQueryService.getRoomOf(userId);

        // V1/V19의 시각 컬럼은 TIMESTAMP(초 정밀도)임. 응답의 나노초와 DB 저장 시 반올림 차이만 허용함.
        // 시각 필드를 제외하지 않고 1초 미만 오차로 비교하며, 이후 조회끼리는 모든 필드가 정확히 같아야 함.
        assertThat(mine).usingRecursiveComparison()
                .withComparatorForType((left, right) ->
                        Duration.between(left, right).abs().compareTo(Duration.ofSeconds(1)) < 0
                                ? 0 : left.compareTo(right), Instant.class)
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(saved);
        assertThat(other).isEqualTo(mine);

        String committedBio = jdbc.queryForObject("SELECT bio FROM users WHERE id = ?", String.class, userId);
        assertThat(committedBio).isEqualTo(OWNER_BIO);
        assertThat(mine.ownerBio()).isEqualTo(committedBio);

        Instant committedAt = jdbc.queryForObject("SELECT updated_at FROM personal_rooms WHERE user_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1, utcCalendar()).toInstant(), userId);
        assertThat(mine.updatedAt()).isEqualTo(committedAt);
        for (var slot : mine.slots()) {
            Instant savedAt = jdbc.queryForObject(
                    "SELECT saved_at FROM room_surface_slots WHERE room_user_id = ? AND slot_type = ?",
                    (rs, rowNum) -> rs.getTimestamp(1, utcCalendar()).toInstant(), userId, slot.slotType());
            assertThat(slot.savedAt()).isEqualTo(savedAt);
        }
        for (var placement : mine.placements()) {
            Instant updatedAt = jdbc.queryForObject(
                    "SELECT updated_at FROM room_item_placements WHERE room_user_id = ? AND user_item_id = ?",
                    (rs, rowNum) -> rs.getTimestamp(1, utcCalendar()).toInstant(), userId, placement.userItemId());
            assertThat(placement.updatedAt()).isEqualTo(updatedAt);
        }
    }

    private static Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
