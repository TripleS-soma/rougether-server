package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.service.HouseJoinService;
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

// 입주 신청 승인/거절 알림을 실제 커밋 경계로 검증(테스트 트랜잭션 없음) - HouseJoinNotificationTest 와 같은 패턴.
@SpringBootTest
class HouseJoinRequestNotificationTest {

    @Autowired private HouseJoinService houseJoinService;
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
        }
        if (houseId != null) {
            jdbcTemplate.update("DELETE FROM house_join_requests WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
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

    private House houseWithOwner(User owner, String inviteCode, String houseName) {
        House house = houseRepository.save(House.create(
                owner, houseName, null, null, 4, inviteCode, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private List<Map<String, Object>> notificationsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT type, ref_id, title, body FROM notification WHERE user_id = ?", userId);
    }

    @Test
    void 입주를_신청하면_방장에게_신청_도착_알림이_간다() {
        User owner = newUser("join-create-noti-owner@rougether.dev", "집주인0");
        User applicant = newUser("join-create-noti-applicant@rougether.dev", "신청자0");
        House house = houseWithOwner(owner, "CRENOTI1", "신청 알림 하우스");

        var request = houseJoinService.requestJoin(applicant.getId(), house.getId());

        List<Map<String, Object>> ownerNotifications = notificationsOf(owner.getId());
        assertThat(ownerNotifications).hasSize(1);
        Map<String, Object> row = ownerNotifications.get(0);
        assertThat(row.get("type")).isEqualTo("HOUSE_JOIN_REQUEST_CREATED");
        assertThat(((Number) row.get("ref_id")).longValue()).isEqualTo(request.requestId());
        assertThat(row.get("title")).isEqualTo("입주 신청 도착");
        assertThat(row.get("body"))
                .isEqualTo("신청자0님이 '신청 알림 하우스'에 입주를 신청했어요. 수락하면 입주가 확정돼요.");
        // 신청자 본인에게는 방장이 판정하기 전까지 아무 알림도 가지 않는다
        assertThat(notificationsOf(applicant.getId())).isEmpty();
    }

    @Test
    void 철회_거절_후_반복_재신청은_억제_창_안에서_방장_알림을_한_번만_보낸다() {
        User owner = newUser("join-dedup-noti-owner@rougether.dev", "집주인4");
        User applicant = newUser("join-dedup-noti-applicant@rougether.dev", "신청자4");
        House house = houseWithOwner(owner, "DUPNOTI1", "중복 알림 하우스");

        // 철회 후 재신청 - 신청 행이 새로 만들어져도(refId 변경) 같은 조합이면 재발송하지 않는다.
        var first = houseJoinService.requestJoin(applicant.getId(), house.getId());
        houseJoinService.withdrawRequest(applicant.getId(), first.requestId());
        var second = houseJoinService.requestJoin(applicant.getId(), house.getId());
        // 거절 후 재신청(reopen)도 마찬가지다.
        houseJoinService.rejectRequest(owner.getId(), house.getId(), second.requestId());
        houseJoinService.requestJoin(applicant.getId(), house.getId());

        List<Map<String, Object>> ownerNotifications = notificationsOf(owner.getId());
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).get("type")).isEqualTo("HOUSE_JOIN_REQUEST_CREATED");
    }

    @Test
    void 닉네임_미설정_신청자의_도착_알림은_이웃으로_표기된다() {
        User owner = newUser("join-noname-noti-owner@rougether.dev", "집주인5");
        User applicant = newUser("join-noname-noti-applicant@rougether.dev", null);
        House house = houseWithOwner(owner, "NONAME01", "무명 하우스");

        houseJoinService.requestJoin(applicant.getId(), house.getId());

        List<Map<String, Object>> ownerNotifications = notificationsOf(owner.getId());
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).get("body"))
                .isEqualTo("이웃님이 '무명 하우스'에 입주를 신청했어요. 수락하면 입주가 확정돼요.");
    }

    @Test
    void 입주_신청을_거절하면_거절_알림은_신청자에게만_간다() {
        User owner = newUser("join-reject-noti-owner@rougether.dev", "집주인");
        User applicant = newUser("join-reject-noti-applicant@rougether.dev", "신청자");
        House house = houseWithOwner(owner, "REJNOTI1", "거절 알림 하우스");
        var request = houseJoinService.requestJoin(applicant.getId(), house.getId());

        houseJoinService.rejectRequest(owner.getId(), house.getId(), request.requestId());

        List<Map<String, Object>> applicantNotifications = notificationsOf(applicant.getId());
        assertThat(applicantNotifications).hasSize(1);
        Map<String, Object> row = applicantNotifications.get(0);
        assertThat(row.get("type")).isEqualTo("HOUSE_JOIN_REQUEST_REJECTED");
        assertThat(((Number) row.get("ref_id")).longValue()).isEqualTo(request.requestId());
        assertThat(row.get("title")).isEqualTo("입주 신청 거절");
        assertThat(row.get("body")).isEqualTo("'거절 알림 하우스' 입주 신청이 거절됐어요.");
        // 방장에게는 신청 도착 알림만 있고, 본인이 내린 거절에 대한 알림은 없다
        List<Map<String, Object>> ownerNotifications = notificationsOf(owner.getId());
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).get("type")).isEqualTo("HOUSE_JOIN_REQUEST_CREATED");
    }

    @Test
    void 입주_신청을_승인하면_신청자에게_승인_알림이_간다() {
        User owner = newUser("join-accept-noti-owner@rougether.dev", "집주인2");
        User applicant = newUser("join-accept-noti-applicant@rougether.dev", "신청자2");
        House house = houseWithOwner(owner, "ACCNOTI1", "승인 알림 하우스");
        var request = houseJoinService.requestJoin(applicant.getId(), house.getId());

        houseJoinService.acceptRequest(owner.getId(), house.getId(), request.requestId());

        List<Map<String, Object>> applicantNotifications = notificationsOf(applicant.getId());
        // 신청자는 승인 알림만 받고, 입주(HOUSE_MEMBER_JOINED)는 기존 멤버(owner)에게만 간다
        assertThat(applicantNotifications).hasSize(1);
        Map<String, Object> row = applicantNotifications.get(0);
        assertThat(row.get("type")).isEqualTo("HOUSE_JOIN_REQUEST_ACCEPTED");
        assertThat(((Number) row.get("ref_id")).longValue()).isEqualTo(request.requestId());
        assertThat(row.get("title")).isEqualTo("입주 신청 승인");
        assertThat(row.get("body")).isEqualTo("'승인 알림 하우스' 입주 신청이 승인됐어요.");

        // 방장에게는 신청 도착 알림과 입주(HOUSE_MEMBER_JOINED) 알림이 남는다
        List<Map<String, Object>> ownerNotifications = notificationsOf(owner.getId());
        assertThat(ownerNotifications)
                .extracting(notification -> notification.get("type"))
                .containsExactlyInAnyOrder("HOUSE_JOIN_REQUEST_CREATED", "HOUSE_MEMBER_JOINED");
    }
}
