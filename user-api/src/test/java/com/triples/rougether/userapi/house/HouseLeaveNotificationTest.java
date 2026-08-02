package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 퇴거 알림(#185)을 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
// 알림 내역은 탈퇴와 같은 트랜잭션에서 동기 저장이라 원자적이고, push 만 커밋 후 비동기(stub sender)다.
@SpringBootTest
class HouseLeaveNotificationTest {

    private static final String TITLE = "멤버 퇴거";

    @Autowired private HouseMemberCommandService houseMemberCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private Long houseId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house_members WHERE user_id = ?", userId);
        }
        if (houseId != null) {
            houseRepository.deleteById(houseId);
        }
        for (Long userId : userIds) {
            userRepository.deleteById(userId);
        }
    }

    private User newUser(String email, String nickname) {
        User user = userRepository.save(User.signUp(email));
        if (nickname != null) {
            user.changeNickname(nickname);
            userRepository.save(user);
        }
        userIds.add(user.getId());
        return user;
    }

    private House newHouse(User owner, String inviteCode) {
        House house = houseRepository.save(House.create(
                owner, "퇴거 알림 하우스", null, null, 4, inviteCode, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        return house;
    }

    private List<Map<String, Object>> notificationsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT type, ref_id, title, body FROM notification WHERE user_id = ?", userId);
    }

    @Test
    void 탈퇴하면_남은_활성_멤버에게만_알림이_간다() {
        User owner = newUser("leave-noti-owner@rougether.dev", "집주인");
        User stayer = newUser("leave-noti-stayer@rougether.dev", "남는사람");
        User leaver = newUser("leave-noti-leaver@rougether.dev", "떠나는사람");
        House house = newHouse(owner, "LEAVNT01");
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, stayer, HouseMemberRole.MEMBER));
        Long leaverMembershipId = houseMemberRepository
                .save(HouseMember.create(house, leaver, HouseMemberRole.MEMBER)).getId();

        houseMemberCommandService.leave(leaver.getId(), house.getId());

        for (Long userId : List.of(owner.getId(), stayer.getId())) {
            List<Map<String, Object>> rows = notificationsOf(userId);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("type")).isEqualTo("HOUSE_MEMBER_LEFT");
            assertThat(((Number) rows.get(0).get("ref_id")).longValue()).isEqualTo(leaverMembershipId);
            assertThat(rows.get(0).get("title")).isEqualTo(TITLE);
            assertThat(rows.get(0).get("body")).isEqualTo("떠나는사람님이 집을 떠났어요.");
        }
        // 탈퇴자 본인은 수신 대상이 아니다
        assertThat(notificationsOf(leaver.getId())).isEmpty();
    }


    @Test
    void 마지막_멤버가_탈퇴하면_발송하지_않는다() {
        User owner = newUser("leave-noti3-owner@rougether.dev", "혼자살이");
        House house = newHouse(owner, "LEAVNT03");
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));

        houseMemberCommandService.leave(owner.getId(), house.getId());

        // 남은 활성 멤버가 0명 - 알릴 상대가 없다
        assertThat(notificationsOf(owner.getId())).isEmpty();
    }

    @Test
    void 강퇴는_퇴거_알림을_보내지_않는다() {
        User owner = newUser("leave-noti4-owner@rougether.dev", "집주인4");
        User stayer = newUser("leave-noti4-stayer@rougether.dev", "남는사람4");
        User kicked = newUser("leave-noti4-kicked@rougether.dev", "쫓겨난사람");
        House house = newHouse(owner, "LEAVNT04");
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, stayer, HouseMemberRole.MEMBER));
        Long kickedMembershipId = houseMemberRepository
                .save(HouseMember.create(house, kicked, HouseMemberRole.MEMBER)).getId();

        houseMemberCommandService.kick(owner.getId(), house.getId(), kickedMembershipId);

        // 강퇴는 이번 plan 범위 밖 - HOUSE_MEMBER_LEFT 로 새어나가면 안 된다
        assertThat(notificationsOf(owner.getId())).isEmpty();
        assertThat(notificationsOf(stayer.getId())).isEmpty();
        assertThat(notificationsOf(kicked.getId())).isEmpty();
    }
}
