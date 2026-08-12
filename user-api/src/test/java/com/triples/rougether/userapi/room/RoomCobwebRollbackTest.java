package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.room.service.RoomCobwebService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 거미줄 상태·보상·원장·알림을 하나의 커밋 경계로 보장하는지 실제 DB로 검증한다.
@SpringBootTest
class RoomCobwebRollbackTest {

    @Autowired private RoomCobwebService roomCobwebService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 마지막 단계인 알림 저장 실패를 주입하되, 나머지 실제 빈 동작은 유지한다.
    @MockitoSpyBean private NotificationService notificationService;

    private Long cleanerUserId;
    private Long roomUserId;
    private Long houseId;
    private Long cleanerMembershipId;
    private Long roomMembershipId;

    @AfterEach
    void cleanup() {
        if (roomUserId != null) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", roomUserId);
            jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ?", roomUserId);
            jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", roomUserId);
        }
        if (cleanerUserId != null) {
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", cleanerUserId);
        }
        if (cleanerMembershipId != null) {
            houseMemberRepository.deleteById(cleanerMembershipId);
        }
        if (roomMembershipId != null) {
            houseMemberRepository.deleteById(roomMembershipId);
        }
        if (houseId != null) {
            houseRepository.deleteById(houseId);
        }
        if (cleanerUserId != null) {
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", cleanerUserId);
            userRepository.deleteById(cleanerUserId);
        }
        if (roomUserId != null) {
            userRepository.deleteById(roomUserId);
        }
    }

    @Test
    void 알림_저장이_실패하면_거미줄과_코인과_원장과_알림이_모두_롤백된다() {
        User cleaner = userRepository.save(User.signUp("cobweb-rollback-cleaner@rougether.dev"));
        cleaner.changeNickname("청소 친구");
        cleaner = userRepository.save(cleaner);
        cleanerUserId = cleaner.getId();
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(cleaner));

        User roomOwner = userRepository.save(User.signUp("cobweb-rollback-owner@rougether.dev"));
        roomUserId = roomOwner.getId();
        personalRoomRepository.save(PersonalRoom.create(roomOwner));

        House house = houseRepository.save(House.create(
                cleaner, "거미줄 롤백 집", null, null, 4, "COBWEBRB",
                Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        cleanerMembershipId = houseMemberRepository
                .save(HouseMember.create(house, cleaner, HouseMemberRole.OWNER)).getId();
        roomMembershipId = houseMemberRepository
                .save(HouseMember.create(house, roomOwner, HouseMemberRole.MEMBER)).getId();

        Instant appearedAt = Instant.now().minusSeconds(60);
        jdbcTemplate.update("""
                INSERT INTO room_cobwebs
                    (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                VALUES (?, ?, NULL, NULL, ?)
                """, roomUserId, Timestamp.from(appearedAt), Timestamp.from(appearedAt));

        doThrow(new RuntimeException("알림 저장 실패")).when(notificationService)
                .send(anyLong(), any(), anyLong());

        assertThatThrownBy(() -> roomCobwebService.cleanHouseMemberRoom(
                cleanerUserId, houseId, roomMembershipId))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleaned_at IS NULL FROM room_cobwebs WHERE room_user_id = ?",
                Boolean.class, roomUserId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND currency_type = 'COIN'",
                Integer.class, cleanerUserId)).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_histories WHERE user_id = ? AND reason = 'COBWEB_CLEAN'",
                Integer.class, cleanerUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ROOM_COBWEB_CLEANED'",
                Integer.class, roomUserId)).isZero();
    }
}
