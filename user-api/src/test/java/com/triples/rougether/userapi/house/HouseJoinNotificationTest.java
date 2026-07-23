package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.service.HouseJoinService;
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

// 입주 알림(#184)을 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
// 알림 내역은 가입과 같은 트랜잭션에서 동기 저장이라 원자적이고, push 만 커밋 후 비동기(stub sender)다.
@SpringBootTest
class HouseJoinNotificationTest {

    private static final String TITLE = "새 멤버 입주";

    @Autowired private HouseJoinService houseJoinService;
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

    // 소유자 membership 까지 만든 집. 소유자가 유일한 "기존 활성 멤버"다.
    private House houseWithOwner(User owner, String inviteCode) {
        House house = houseRepository.save(House.create(
                owner, "입주 알림 하우스", null, null, 4, inviteCode, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private List<Map<String, Object>> notificationsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT type, ref_id, title, body FROM notification WHERE user_id = ?", userId);
    }

    private void assertJoinedNotification(Map<String, Object> row, Long membershipId, String nickname) {
        assertThat(row.get("type")).isEqualTo("HOUSE_MEMBER_JOINED");
        assertThat(((Number) row.get("ref_id")).longValue()).isEqualTo(membershipId);
        assertThat(row.get("title")).isEqualTo(TITLE);
        assertThat(row.get("body")).isEqualTo(nickname + "님이 집에 입주했어요.");
    }

    @Test
    void 초대코드_가입은_기존_활성_멤버에게만_알림이_간다() {
        User owner = newUser("join-noti-owner@rougether.dev", "집주인");
        User existing = newUser("join-noti-existing@rougether.dev", "먼저온사람");
        User joiner = newUser("join-noti-joiner@rougether.dev", "새친구");
        House house = houseWithOwner(owner, "JOINNT01");
        houseMemberRepository.save(HouseMember.create(house, existing, HouseMemberRole.MEMBER));

        Long membershipId = houseJoinService.joinByCode(joiner.getId(), "JOINNT01").membershipId();

        assertThat(notificationsOf(owner.getId())).hasSize(1);
        assertJoinedNotification(notificationsOf(owner.getId()).get(0), membershipId, "새친구");
        assertThat(notificationsOf(existing.getId())).hasSize(1);
        assertJoinedNotification(notificationsOf(existing.getId()).get(0), membershipId, "새친구");
        // 신규 멤버 본인은 수신 대상이 아니다
        assertThat(notificationsOf(joiner.getId())).isEmpty();
    }

    @Test
    void houseId_직접_가입도_같은_알림이_간다() {
        User owner = newUser("join-noti2-owner@rougether.dev", "집주인2");
        User joiner = newUser("join-noti2-joiner@rougether.dev", "새친구2");
        House house = houseWithOwner(owner, "JOINNT02");

        Long membershipId = houseJoinService.join(joiner.getId(), house.getId()).membershipId();

        assertThat(notificationsOf(owner.getId())).hasSize(1);
        assertJoinedNotification(notificationsOf(owner.getId()).get(0), membershipId, "새친구2");
        assertThat(notificationsOf(joiner.getId())).isEmpty();
    }


    @Test
    void 탈퇴했던_멤버의_재가입도_입주로_보고_발송한다() {
        User owner = newUser("join-noti6-owner@rougether.dev", "집주인6");
        User rejoiner = newUser("join-noti6-rejoiner@rougether.dev", "돌아온사람");
        House house = houseWithOwner(owner, "JOINNT06");
        Long membershipId = houseJoinService.join(rejoiner.getId(), house.getId()).membershipId();
        houseMemberCommandService.leave(rejoiner.getId(), house.getId());
        jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", owner.getId());

        // 재활성화(새 row 아님)여도 입주 알림 대상 - refId 는 기존 membershipId 그대로
        Long rejoinedMembershipId = houseJoinService.join(rejoiner.getId(), house.getId()).membershipId();

        assertThat(rejoinedMembershipId).isEqualTo(membershipId);
        assertThat(notificationsOf(owner.getId())).hasSize(1);
        assertJoinedNotification(notificationsOf(owner.getId()).get(0), membershipId, "돌아온사람");
        assertThat(notificationsOf(rejoiner.getId())).isEmpty();
    }

    @Test
    void 기존_활성_멤버가_없으면_발송하지_않는다() {
        // membership row 없는 집 - 첫 입주자에겐 알릴 상대가 없다
        User owner = newUser("join-noti4-owner@rougether.dev", "집주인4");
        User joiner = newUser("join-noti4-joiner@rougether.dev", "첫입주");
        House house = houseRepository.save(House.create(
                owner, "빈 하우스", null, null, 4, "JOINNT04", Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();

        houseJoinService.join(joiner.getId(), house.getId());

        assertThat(notificationsOf(owner.getId())).isEmpty();
        assertThat(notificationsOf(joiner.getId())).isEmpty();
    }

    @Test
    void 가입에_실패하면_알림도_저장되지_않는다() {
        User owner = newUser("join-noti5-owner@rougether.dev", "집주인5");
        User joiner = newUser("join-noti5-joiner@rougether.dev", "중복가입");
        House house = houseWithOwner(owner, "JOINNT05");
        houseJoinService.join(joiner.getId(), house.getId());
        assertThat(notificationsOf(owner.getId())).hasSize(1);

        // 이미 활성 멤버라 409 - 재가입이 아니므로 입주 알림이 또 나가면 안 된다
        assertThatThrownBy(() -> houseJoinService.join(joiner.getId(), house.getId()))
                .isInstanceOf(BusinessException.class);

        assertThat(notificationsOf(owner.getId())).hasSize(1);
    }
}
