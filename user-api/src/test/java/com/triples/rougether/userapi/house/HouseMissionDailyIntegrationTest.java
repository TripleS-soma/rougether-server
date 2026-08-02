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
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionClaimResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionContributeResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// DAILY 미션 일일 달성률 모델 (#201) - 오늘(KST) 기여 비율 판정, 매일 claim(+20) 반복, COMPLETED 전환 없음.
@SpringBootTest
@Transactional
class HouseMissionDailyIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private HouseMissionService houseMissionService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private record Fixture(User owner, User member, House house) {
    }

    // 멤버 2명(owner + member) 집 - 1명 기여 = 50%, 2명 기여 = 100%
    private Fixture fixture() {
        User owner = userRepository.save(User.signUp("daily-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("daily-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "데일리 하우스", null, null, 4, "DAILY234", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        return new Fixture(owner, member, house);
    }

    private Long createDaily(Fixture f, int targetPercent) {
        return houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("데일리 미션", HouseMissionType.DAILY_MEMBER_RATE,
                        targetPercent, null, null)).missionId();
    }

    // 오늘 기여 이력을 어제로 되돌린다 - 자정 경계(날짜 리셋) 시뮬레이션
    private void rewindContributionsToYesterday(Long missionId) {
        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update(
                "update house_mission_daily_contributions set contribution_date = ? where mission_id = ?",
                Date.valueOf(LocalDate.now(KST).minusDays(1)), missionId);
    }

    private void rewindRewardsToYesterday(Long missionId) {
        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update(
                "update house_mission_daily_rewards set reward_date = ? where mission_id = ?",
                Date.valueOf(LocalDate.now(KST).minusDays(1)), missionId);
    }

    @Test
    void DAILY_등록은_target_100_초과면_400() {
        Fixture f = fixture();
        assertThatThrownBy(() -> createDaily(f, 101))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_TARGET_INVALID));

        // 경계값 100 은 허용, WEEKLY 는 기존 1~1000 유지
        assertThat(createDaily(f, 100)).isNotNull();
        assertThat(houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT,
                        1000, null, null)).missionId()).isNotNull();
    }

    @Test
    void DAILY_판정은_오늘_기여_멤버_비율이_정확히_target_이상이면_달성() {
        Fixture f = fixture();
        Long missionId = createDaily(f, 50); // 멤버 2명 -> 1명 기여 = 정확히 50%

        HouseMissionContributeResponse first =
                houseMissionService.contribute(f.owner().getId(), f.house().getId(), missionId);
        assertThat(first.currentValue()).isEqualTo(50);
        assertThat(first.achieved()).isTrue();

        HouseMissionContributeResponse second =
                houseMissionService.contribute(f.member().getId(), f.house().getId(), missionId);
        assertThat(second.currentValue()).isEqualTo(100);
        assertThat(second.achieved()).isTrue();
    }

    @Test
    void DAILY_판정은_target_미만이면_미달성() {
        Fixture f = fixture();
        Long missionId = createDaily(f, 51); // 멤버 2명 -> 1명 기여(50%)로는 미달

        HouseMissionContributeResponse response =
                houseMissionService.contribute(f.owner().getId(), f.house().getId(), missionId);
        assertThat(response.currentValue()).isEqualTo(50);
        assertThat(response.achieved()).isFalse();

        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), missionId))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED));
        assertThat(houseRepository.findById(f.house().getId()).orElseThrow().getGrowthPoints()).isZero();
    }

    @Test
    void 어제_기여는_오늘_판정에_포함되지_않는다() {
        Fixture f = fixture();
        Long missionId = createDaily(f, 50);
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), missionId);
        rewindContributionsToYesterday(missionId);

        HouseMissionResponse detail =
                houseMissionService.getMission(f.owner().getId(), f.house().getId(), missionId);
        assertThat(detail.currentValue()).isZero();
        assertThat(detail.achieved()).isFalse();
        // 어제 기여와 무관하게 내 누적 기여는 유지된다
        assertThat(detail.myContribution()).isEqualTo(1);
    }

    @Test
    void DAILY_claim_은_20포인트_지급하고_COMPLETED_로_전환되지_않는다() {
        Fixture f = fixture();
        Long missionId = createDaily(f, 50);
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), missionId);

        HouseMissionClaimResponse claimed =
                houseMissionService.claim(f.member().getId(), f.house().getId(), missionId);
        assertThat(claimed.grantedGrowthPoints()).isEqualTo(20);
        assertThat(claimed.houseGrowthPoints()).isEqualTo(20);
        assertThat(claimed.status()).isEqualTo(HouseMissionStatus.ACTIVE);

        // claim 이후에도 기여 가능(ACTIVE 유지), 오늘 재claim 은 409
        houseMissionService.contribute(f.member().getId(), f.house().getId(), missionId);
        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), missionId))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED));
    }

    @Test
    void 다음날이_되면_다시_기여하고_다시_claim_할_수_있다() {
        Fixture f = fixture();
        Long missionId = createDaily(f, 50);
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), missionId);
        houseMissionService.claim(f.owner().getId(), f.house().getId(), missionId);

        // 자정이 지난 상황 시뮬레이션 - 기여·보상 이력이 모두 어제 것이 된다
        rewindContributionsToYesterday(missionId);
        rewindRewardsToYesterday(missionId);

        // 오늘 판정은 0% 부터 다시 시작 - 미달 상태에선 claim 불가
        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), missionId))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED));

        // 오늘 다시 기여하면 다시 달성·claim 가능 (+20 재지급, 누적 40)
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), missionId);
        HouseMissionClaimResponse again =
                houseMissionService.claim(f.member().getId(), f.house().getId(), missionId);
        assertThat(again.grantedGrowthPoints()).isEqualTo(20);
        assertThat(again.houseGrowthPoints()).isEqualTo(40);
    }

    @Test
    void 기여_후_탈퇴한_멤버는_오늘_판정에서_제외된다() {
        Fixture f = fixture();
        Long missionId = createDaily(f, 100); // 멤버 2명 전원 기여해야 달성
        houseMissionService.contribute(f.member().getId(), f.house().getId(), missionId);

        // 기여한 멤버가 탈퇴 - 분모(활성 2->1)와 함께 분자에서도 빠져야 한다
        houseMemberRepository.findByHouseIdAndUserId(f.house().getId(), f.member().getId())
                .orElseThrow()
                .leave();
        f.house().decreaseMemberCount();
        entityManager.flush();

        HouseMissionResponse detail =
                houseMissionService.getMission(f.owner().getId(), f.house().getId(), missionId);
        // 탈퇴자 기여가 남으면 1/1=100% 로 왜곡 - 제외되어 0% 여야 한다
        assertThat(detail.currentValue()).isZero();
        assertThat(detail.achieved()).isFalse();

        assertThatThrownBy(() -> houseMissionService.claim(f.owner().getId(), f.house().getId(), missionId))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED));
    }

    @Test
    void 목록과_상세는_DAILY_에만_todayClaimed_를_내려준다() {
        Fixture f = fixture();
        Long dailyId = createDaily(f, 50);
        Long weeklyId = houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT,
                        20, null, null)).missionId();
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), dailyId);
        houseMissionService.claim(f.owner().getId(), f.house().getId(), dailyId);

        HouseMissionResponse detail =
                houseMissionService.getMission(f.owner().getId(), f.house().getId(), dailyId);
        assertThat(detail.todayClaimed()).isTrue();
        assertThat(detail.currentValue()).isEqualTo(50);

        HouseMissionListResponse list = houseMissionService.getMissions(f.owner().getId(), f.house().getId());
        HouseMissionListResponse.MissionSummary dailyRow = list.items().stream()
                .filter(item -> item.missionId().equals(dailyId)).findFirst().orElseThrow();
        HouseMissionListResponse.MissionSummary weeklyRow = list.items().stream()
                .filter(item -> item.missionId().equals(weeklyId)).findFirst().orElseThrow();
        assertThat(dailyRow.todayClaimed()).isTrue();
        assertThat(dailyRow.currentValue()).isEqualTo(50);
        assertThat(weeklyRow.todayClaimed()).isNull();
    }
}
