package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionClaimResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionContributeResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 단체 미션 전체 흐름 회귀 - 등록→기여(하루 1회)→달성→claim(성장 포인트 +100, 재클레임 불가)을 실제 DB(H2)로 검증.
@SpringBootTest
@Transactional
class HouseMissionFlowIntegrationTest {

    @Autowired private HouseMissionService houseMissionService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseMissionParticipantRepository participantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private record Fixture(User owner, User member, House house, HouseMember ownerRow, HouseMember memberRow) {
    }

    private Fixture fixture() {
        User owner = userRepository.save(User.signUp("mission-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("mission-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "미션 하우스", null, null, 4, "MSSN2345", Instant.now().plus(Duration.ofDays(7))));
        HouseMember ownerRow = houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        HouseMember memberRow = houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        return new Fixture(owner, member, house, ownerRow, memberRow);
    }

    @Test
    void 등록부터_claim_까지_전체_흐름() {
        Fixture f = fixture();
        HouseMissionResponse created = houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 2, null, null));
        assertThat(created.status()).isEqualTo(HouseMissionStatus.ACTIVE);

        // 소유자·구성원 각 1회 기여 → 목표(2) 달성
        HouseMissionContributeResponse first =
                houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId());
        assertThat(first.currentValue()).isEqualTo(1);
        assertThat(first.achieved()).isFalse();
        HouseMissionContributeResponse second =
                houseMissionService.contribute(f.member().getId(), f.house().getId(), created.missionId());
        assertThat(second.currentValue()).isEqualTo(2);
        assertThat(second.achieved()).isTrue();

        // 같은 날 재기여는 409
        assertThatThrownBy(() -> houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED));

        // 구성원(비소유자)이 claim → COMPLETED + 성장 포인트 100 + 레벨 1
        HouseMissionClaimResponse claimed =
                houseMissionService.claim(f.member().getId(), f.house().getId(), created.missionId());
        assertThat(claimed.status()).isEqualTo(HouseMissionStatus.COMPLETED);
        assertThat(claimed.grantedGrowthPoints()).isEqualTo(100);
        assertThat(claimed.houseGrowthPoints()).isEqualTo(100);
        assertThat(claimed.houseLevel()).isEqualTo(1);
        assertThat(f.house().getGrowthPoints()).isEqualTo(100);
        assertThat(f.house().getLevel()).isEqualTo(1);
        participantRepository.findByMissionId(created.missionId())
                .forEach(participant -> assertThat(participant.isRewardClaimed()).isTrue());

        // 재클레임은 409, 성장 포인트 그대로
        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED));
        assertThat(f.house().getGrowthPoints()).isEqualTo(100);

        // COMPLETED 미션엔 기여 불가
        assertThatThrownBy(() -> houseMissionService.contribute(f.member().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));
    }

    @Test
    void 어제_기여한_구성원은_오늘_다시_기여할_수_있다() {
        Fixture f = fixture();
        HouseMissionResponse created = houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 10, null, null));
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId());

        // 기여 이력 날짜를 어제로 되돌린다 (KST 하루 1회 판정 기준 = 일별 이력 UNIQUE, #201).
        // 1차 캐시에 남은 이력 row 가 JDBC 수정을 가리지 않도록 flush 후 비운다.
        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update(
                "update house_mission_daily_contributions set contribution_date = ? where mission_id = ?",
                java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1)),
                created.missionId());

        HouseMissionContributeResponse again =
                houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId());
        assertThat(again.myContribution()).isEqualTo(2);
        assertThat(again.currentValue()).isEqualTo(2);
    }

    @Test
    void 기간이_지난_미션엔_기여할_수_없다() {
        Fixture f = fixture();
        Instant now = Instant.now();
        HouseMissionResponse created = houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("지난 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 2,
                        now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1))));

        assertThatThrownBy(() -> houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));
    }

    @Test
    void 기간이_지난_미션은_목표를_달성했어도_claim_할_수_없다() {
        Fixture f = fixture();
        Instant now = Instant.now();
        // 기간 내 목표(2)를 달성해놓고 claim 하지 않은 채 기간이 끝난 상황 (#205 - 유예 없음)
        HouseMissionResponse created = houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("만료 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 2,
                        null, now.plus(Duration.ofDays(1))));
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId());
        houseMissionService.contribute(f.member().getId(), f.house().getId(), created.missionId());

        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update("update house_missions set ends_at = ? where id = ?",
                Timestamp.from(now.minus(Duration.ofDays(1))), created.missionId());

        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));
        // 성장 포인트 미지급
        assertThat(houseRepository.findById(f.house().getId()).orElseThrow().getGrowthPoints()).isZero();
    }

    @Test
    void EXPIRED_전이된_미션은_기여와_claim_이_모두_거부된다() {
        Fixture f = fixture();
        HouseMissionResponse created = houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("만료 전이 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 2,
                        null, Instant.now().plus(Duration.ofDays(1))));

        // 만료 전이 배치가 EXPIRED 로 내린 상태 시뮬레이션
        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update("update house_missions set status = 'EXPIRED' where id = ?", created.missionId());

        assertThatThrownBy(() -> houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));
        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));

        // 목록에는 EXPIRED 상태로 그대로 노출된다
        assertThat(houseMissionService.getMissions(f.owner().getId(), f.house().getId()).items())
                .anySatisfy(item -> {
                    assertThat(item.missionId()).isEqualTo(created.missionId());
                    assertThat(item.status()).isEqualTo(HouseMissionStatus.EXPIRED);
                });
    }
}
