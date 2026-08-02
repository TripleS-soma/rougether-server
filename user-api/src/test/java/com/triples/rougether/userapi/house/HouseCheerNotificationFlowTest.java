package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.service.HouseCheerService;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 응원-알림 정합성(#173)을 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
// 알림 내역은 응원과 같은 트랜잭션에서 동기 저장(spec 계약)이라 원자적이고, push 만 커밋 후 비동기(stub sender)다.
@SpringBootTest
class HouseCheerNotificationFlowTest {

    @Autowired private HouseCheerService houseCheerService;
    @Autowired private HouseMemberCheerRepository houseMemberCheerRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 실패 주입용 spy - 실제 빈에 위임하므로 정상 케이스 동작은 그대로다.
    @MockitoSpyBean private NotificationService notificationService;

    private Long senderId;
    private Long targetUserId;
    private Long houseId;
    private Long senderMembershipId;
    private Long targetMembershipId;
    private Long cheerId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함. 알림은 리스너가 아닌 진입점이 만들어 대상 유저 기준으로 지운다.
        if (targetUserId != null) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", targetUserId);
        }
        if (cheerId != null) {
            houseMemberCheerRepository.deleteById(cheerId);
        }
        if (senderMembershipId != null) {
            houseMemberRepository.deleteById(senderMembershipId);
        }
        if (targetMembershipId != null) {
            houseMemberRepository.deleteById(targetMembershipId);
        }
        if (houseId != null) {
            houseRepository.deleteById(houseId);
        }
        if (senderId != null) {
            userRepository.deleteById(senderId);
        }
        if (targetUserId != null) {
            userRepository.deleteById(targetUserId);
        }
    }

    private HouseMember prepareMembers(String senderEmail, String targetEmail, String houseName, String code) {
        User sender = userRepository.save(User.signUp(senderEmail));
        sender.changeNickname("보낸이");
        userRepository.save(sender);
        senderId = sender.getId();
        User target = userRepository.save(User.signUp(targetEmail));
        targetUserId = target.getId();
        House house = houseRepository.save(House.create(
                sender, houseName, null, null, 4, code, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        senderMembershipId = houseMemberRepository
                .save(HouseMember.create(house, sender, HouseMemberRole.OWNER)).getId();
        HouseMember targetMember = houseMemberRepository
                .save(HouseMember.create(house, target, HouseMemberRole.MEMBER));
        targetMembershipId = targetMember.getId();
        return targetMember;
    }

    @Test
    void 응원이_커밋되면_대상에게_FRIEND_CHEER_알림_내역이_남는다() {
        HouseMember targetMember = prepareMembers(
                "cheer-flow-sender@rougether.dev", "cheer-flow-target@rougether.dev", "응원 알림 하우스", "CHEER456");

        HouseCheerResponse response = houseCheerService.cheer(
                senderId, houseId, targetMember.getId(), "best");
        cheerId = response.cheerId();

        // 내역은 응원과 같은 트랜잭션에서 동기 저장되므로 반환 직후 존재한다(push 만 비동기).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type, ref_id, title, body FROM notification WHERE user_id = ?", targetUserId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("FRIEND_CHEER");
        assertThat(((Number) rows.get(0).get("ref_id")).longValue()).isEqualTo(cheerId);
        assertThat(rows.get(0).get("title")).isEqualTo("응원이 도착했어요");
        assertThat(rows.get(0).get("body")).isEqualTo("보낸이님: 오늘도 최고!");
    }

    @Test
    void 알림_내역_저장이_실패하면_응원도_함께_롤백된다() {
        HouseMember targetMember = prepareMembers(
                "cheer-fail-sender@rougether.dev", "cheer-fail-target@rougether.dev", "응원 실패 하우스", "CHEER567");

        doThrow(new RuntimeException("알림 저장 실패")).when(notificationService)
                .send(anyLong(), any(), anyLong());

        // 내역 저장은 응원과 원자적(spec: 내역 동기 저장) - 반쪽 성공(응원만 저장) 상태를 만들지 않는다
        assertThatThrownBy(() -> houseCheerService.cheer(
                senderId, houseId, targetMember.getId(), "support"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM house_member_cheers WHERE sender_user_id = ?", senderId)).isEmpty();
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM notification WHERE user_id = ?", targetUserId)).isEmpty();
    }
}
