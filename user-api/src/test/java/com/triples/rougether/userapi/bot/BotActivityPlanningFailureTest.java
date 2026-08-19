package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 판정(읽기) 단계 예외 격리(#310): 한 봇의 판정 조회가 던져도 같은 봇의 다른 행동과 다음 봇은 계속 처리된다.
// 쓰기 단계 격리(BusinessException 거절)는 BotActivityServiceTest 가 본다. 스파이 빈 때문에 컨텍스트가 분리되므로 별도 클래스.
@SpringBootTest
class BotActivityPlanningFailureTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired BotActivityService botActivityService;
    @MockitoSpyBean BotSocialActions botSocialActions;
    @Autowired UserRepository userRepository;
    @Autowired UserWalletRepository userWalletRepository;
    @Autowired HouseRepository houseRepository;
    @Autowired HouseMemberRepository houseMemberRepository;
    @Autowired PersonalRoomRepository personalRoomRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private Long houseId;

    @AfterEach
    void cleanup() {
        if (houseId != null) {
            jdbcTemplate.update("DELETE FROM house_member_cheers WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM room_guestbooks WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ? OR cleaned_by_user_id = ?", userId, userId);
            jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void 판정_조회가_예외를_던져도_같은_봇의_다른_행동과_다음_봇은_계속_처리된다() {
        LocalDate today = LocalDate.now(KST);
        User first = bot("bot-latte");  // SPREAD, id 가 작아 먼저 처리된다
        User second = bot("bot-pine");  // SPREAD
        User owner = human("plan-owner");
        House house = house(owner, 4);
        join(house, first);
        join(house, second);
        personalRoomRepository.save(PersonalRoom.create(owner));
        Instant appearedAt = Instant.now().minusSeconds(3600);
        jdbcTemplate.update("""
                INSERT INTO room_cobwebs (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                VALUES (?, ?, NULL, NULL, ?)
                """, owner.getId(), Timestamp.from(appearedAt), Timestamp.from(appearedAt));
        // 첫 봇이 그 틱에 거미줄을 치우기로 판정되는 틱을 고른다 → 같은 봇의 앞선 응원 판정이 던져도 청소는 진행돼야 한다
        int tick = BotDecision.activeTicks(BotActivityProfile.SPREAD).stream()
                .filter(t -> BotDecision.shouldCleanCobweb(first.getId(), today, t, owner.getId()))
                .findFirst().orElseThrow();
        doThrow(new IllegalStateException("판정 조회 장애 시뮬레이션"))
                .when(botSocialActions).cheersDue(argThat(context -> context != null && context.botId() == first.getId()), any());

        BotTickReport report = botActivityService.runTick(today.atTime(LocalTime.ofSecondOfDay(tick * 600L)).atZone(KST));

        assertThat(report.failures()).isEqualTo(1);           // 첫 봇의 응원 판정 1건만 실패로 집계
        assertThat(report.cobwebsCleaned()).isEqualTo(1);     // 같은 봇의 뒤 행동(거미줄 청소)은 진행
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleaned_by_user_id FROM room_cobwebs WHERE room_user_id = ?", Long.class, owner.getId()))
                .isEqualTo(first.getId());
        assertThat(report.botsActed()).isEqualTo(2);          // 다음 봇도 처리
        verify(botSocialActions).cheersDue(argThat(context -> context != null && context.botId() == second.getId()), any());
    }

    private User human(String prefix) {
        User user = userRepository.save(User.signUp(prefix + "-" + UUID.randomUUID() + "@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private User bot(String botKey) {
        User user = userRepository.save(User.bot(botKey, "판정 테스트 " + botKey, null));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private House house(User owner, int maxMembers) {
        String code = ("BP" + UUID.randomUUID().toString().replace("-", "")).substring(0, 8).toUpperCase();
        House house = houseRepository.save(House.create(
                owner, "판정 격리 집", null, null, maxMembers, code, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private void join(House house, User bot) {
        houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseRepository.save(house);
    }
}
