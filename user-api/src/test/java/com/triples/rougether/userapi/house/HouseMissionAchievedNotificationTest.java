package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.service.HouseMissionService;
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

// 단체 미션 목표 도달 알림(#183)을 실제 커밋 경계로 검증(테스트 트랜잭션 없음).
// 알림 내역은 기여와 같은 트랜잭션에서 동기 저장이라 원자적이고, push 만 커밋 후 비동기(stub sender)다.
@SpringBootTest
class HouseMissionAchievedNotificationTest {

    private static final String TITLE = "단체 미션 달성!";

    @Autowired private HouseMissionService houseMissionService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 실패 주입용 spy - 실제 빈에 위임하므로 정상 케이스 동작은 그대로다.
    @MockitoSpyBean private NotificationService notificationService;

    private Long houseId;
    private Long ownerId;
    private Long memberId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (houseId != null) {
            jdbcTemplate.update("DELETE FROM house_mission_participants WHERE mission_id IN "
                    + "(SELECT id FROM house_missions WHERE house_id = ?)", houseId);
            jdbcTemplate.update("DELETE FROM house_missions WHERE house_id = ?", houseId);
        }
        for (Long userId : new Long[] {ownerId, memberId}) {
            if (userId != null) {
                jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM house_members WHERE user_id = ?", userId);
            }
        }
        if (houseId != null) {
            houseRepository.deleteById(houseId);
        }
        for (Long userId : new Long[] {ownerId, memberId}) {
            if (userId != null) {
                userRepository.deleteById(userId);
            }
        }
    }

    // 소유자 + 일반 멤버 2인 집. 목표치 2 = 두 명이 각 1회 기여하면 도달.
    private void prepareHouse(String prefix, String inviteCode) {
        User owner = userRepository.save(User.signUp(prefix + "-owner@rougether.dev"));
        ownerId = owner.getId();
        User member = userRepository.save(User.signUp(prefix + "-member@rougether.dev"));
        memberId = member.getId();
        House house = houseRepository.save(House.create(
                owner, "미션 알림 하우스", null, null, 4, inviteCode, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
    }

    private Long createMission(int targetValue) {
        HouseMissionResponse created = houseMissionService.create(ownerId, houseId,
                new HouseMissionCreateRequest("설거지하기", HouseMissionType.WEEKLY_MEMBER_COUNT,
                        targetValue, null, null));
        return created.missionId();
    }

    private List<Map<String, Object>> notificationsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT type, ref_id, title, body FROM notification WHERE user_id = ?", userId);
    }

    @Test
    void 목표에_도달하면_활성_멤버_전원에게_한_건씩_저장된다() {
        prepareHouse("mission-achieved", "MSNOTI01");
        Long missionId = createMission(2);

        houseMissionService.contribute(ownerId, houseId, missionId);
        // 미도달 기여 시점엔 아직 아무도 못 받는다
        assertThat(notificationsOf(ownerId)).isEmpty();
        assertThat(notificationsOf(memberId)).isEmpty();

        houseMissionService.contribute(memberId, houseId, missionId);

        // 마지막 기여자 본인(memberId) 포함 전원 1건씩
        for (Long userId : List.of(ownerId, memberId)) {
            List<Map<String, Object>> rows = notificationsOf(userId);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("type")).isEqualTo("HOUSE_MISSION_ACHIEVED");
            assertThat(((Number) rows.get(0).get("ref_id")).longValue()).isEqualTo(missionId);
            assertThat(rows.get(0).get("title")).isEqualTo(TITLE);
            assertThat(rows.get(0).get("body"))
                    .isEqualTo("'설거지하기' 미션이 목표를 달성했어요. 보상을 받아보세요!");
        }
    }

    @Test
    void 이미_도달한_미션에_추가_기여해도_다시_발송하지_않는다() {
        prepareHouse("mission-once", "MSNOTI02");
        // 목표 1 - 소유자 1회 기여로 즉시 도달, 이후 멤버 기여는 초과분이라 재발송 대상이 아니다
        Long missionId = createMission(1);

        houseMissionService.contribute(ownerId, houseId, missionId);
        assertThat(notificationsOf(ownerId)).hasSize(1);

        houseMissionService.contribute(memberId, houseId, missionId);

        assertThat(notificationsOf(ownerId)).hasSize(1);
        assertThat(notificationsOf(memberId)).hasSize(1);
    }

    @Test
    void 알림_저장이_실패하면_기여도_함께_롤백된다() {
        prepareHouse("mission-rollback", "MSNOTI03");
        Long missionId = createMission(1);

        doThrow(new RuntimeException("알림 저장 실패")).when(notificationService)
                .send(anyLong(), any(), any(), any(), anyLong());

        assertThatThrownBy(() -> houseMissionService.contribute(ownerId, houseId, missionId))
                .isInstanceOf(RuntimeException.class);

        // 기여 row 도 남지 않아야 한다 - 반쪽 성공(기여만 저장) 금지
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM house_mission_participants WHERE mission_id = ?", missionId)).isEmpty();
        assertThat(notificationsOf(ownerId)).isEmpty();
    }
}
