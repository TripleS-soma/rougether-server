package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

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

// 응원 커밋 후 알림 발송 흐름(#173) - AFTER_COMMIT 리스너가 실제 커밋 경계에서 알림 내역을 남기는지 검증(테스트 트랜잭션 없음).
// push 는 stub sender(테스트 프로필) 비동기 best-effort 라 내역 row 존재까지만 확인한다.
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
        // 테스트 트랜잭션이 없어 직접 정리함. 알림은 리스너가 만들어 id 추적이 안 되니 대상 유저 기준으로 지운다.
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

    @Test
    void 응원이_커밋되면_대상에게_FRIEND_CHEER_알림_내역이_남는다() {
        User sender = userRepository.save(User.signUp("cheer-flow-sender@rougether.dev"));
        senderId = sender.getId();
        User target = userRepository.save(User.signUp("cheer-flow-target@rougether.dev"));
        targetUserId = target.getId();
        House house = houseRepository.save(House.create(
                sender, "응원 알림 하우스", null, null, 4, "CHEER456", Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        senderMembershipId = houseMemberRepository
                .save(HouseMember.create(house, sender, HouseMemberRole.OWNER)).getId();
        HouseMember targetMember = houseMemberRepository
                .save(HouseMember.create(house, target, HouseMemberRole.MEMBER));
        targetMembershipId = targetMember.getId();

        HouseCheerResponse response = houseCheerService.cheer(
                sender.getId(), house.getId(), targetMember.getId(), "best");
        cheerId = response.cheerId();

        // AFTER_COMMIT 리스너는 요청 스레드에서 동기 실행되므로 반환 직후 내역이 존재해야 한다(push 만 비동기).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type, ref_id, title, body FROM notification WHERE user_id = ?", targetUserId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("FRIEND_CHEER");
        assertThat(((Number) rows.get(0).get("ref_id")).longValue()).isEqualTo(cheerId);
        assertThat(rows.get(0).get("title")).isEqualTo("응원이 도착했어요");
        // 온보딩 전(닉네임 null) 보낸이는 "집 친구"로 표시
        assertThat(rows.get(0).get("body")).isEqualTo("집 친구님: 오늘도 최고!");
    }

    @Test
    void 알림_발송이_실패해도_응원_요청은_성공하고_응원은_저장된다() {
        User sender = userRepository.save(User.signUp("cheer-fail-sender@rougether.dev"));
        senderId = sender.getId();
        User target = userRepository.save(User.signUp("cheer-fail-target@rougether.dev"));
        targetUserId = target.getId();
        House house = houseRepository.save(House.create(
                sender, "응원 실패 하우스", null, null, 4, "CHEER567", Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        senderMembershipId = houseMemberRepository
                .save(HouseMember.create(house, sender, HouseMemberRole.OWNER)).getId();
        HouseMember targetMember = houseMemberRepository
                .save(HouseMember.create(house, target, HouseMemberRole.MEMBER));
        targetMembershipId = targetMember.getId();

        doThrow(new RuntimeException("알림 저장 실패")).when(notificationService)
                .send(anyLong(), any(), any(), any(), anyLong());

        // 알림(REQUIRES_NEW 새 트랜잭션) 실패가 이미 커밋된 응원 요청으로 전파되면 안 된다
        HouseCheerResponse response = houseCheerService.cheer(
                sender.getId(), house.getId(), targetMember.getId(), "support");
        cheerId = response.cheerId();

        assertThat(houseMemberCheerRepository.findById(cheerId)).isPresent();
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM notification WHERE user_id = ?", targetUserId)).isEmpty();
    }
}
