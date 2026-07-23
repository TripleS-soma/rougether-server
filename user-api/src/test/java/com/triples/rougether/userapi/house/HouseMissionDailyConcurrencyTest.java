package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// DAILY 미션의 동시 기여/claim 이 이중 기록·이중 지급되지 않는지 검증 (#201).
// 미션 행 락 + 일별 UNIQUE 가 방어선. 락은 커밋 시점에 풀리므로 @Transactional 테스트로는 검증 불가 —
// 실제 커밋 + 수동 정리로 검증한다 (ItemSlotRegisterConcurrencyTest 패턴).
@SpringBootTest
class HouseMissionDailyConcurrencyTest {

    @Autowired private HouseMissionService houseMissionService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long houseId;
    private Long ownerId;
    private Long memberId;
    private Long missionId;

    @AfterEach
    void cleanup() {
        if (missionId != null) {
            jdbcTemplate.update("DELETE FROM house_mission_daily_rewards WHERE mission_id = ?", missionId);
            jdbcTemplate.update("DELETE FROM house_mission_daily_contributions WHERE mission_id = ?", missionId);
            jdbcTemplate.update("DELETE FROM house_mission_participants WHERE mission_id = ?", missionId);
            jdbcTemplate.update("DELETE FROM house_missions WHERE id = ?", missionId);
        }
        if (houseId != null) {
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        }
        if (ownerId != null) {
            userRepository.deleteById(ownerId);
        }
        if (memberId != null) {
            userRepository.deleteById(memberId);
        }
    }

    private void setUpDailyMission(int targetPercent) {
        User owner = userRepository.save(User.signUp("daily-race-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("daily-race-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "데일리 경합 하우스", null, null, 4, "DRACE234", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
        jdbcTemplate.update("UPDATE house SET current_member_count = 2 WHERE id = ?", house.getId());
        ownerId = owner.getId();
        memberId = member.getId();
        houseId = house.getId();
        missionId = houseMissionService.create(ownerId, houseId,
                new HouseMissionCreateRequest("경합 데일리", HouseMissionType.DAILY_MEMBER_RATE,
                        targetPercent, null, null)).missionId();
    }

    @Test
    void 동시에_기여해도_하루_1회만_기록된다() throws Exception {
        setUpDailyMission(50);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    houseMissionService.contribute(ownerId, houseId, missionId);
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 하루 1회 초과분은 409 - 본검증은 아래 count 로 수행
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 핵심 불변식: 이력 1행 + 누적 1 - 미션 행 락 + UNIQUE(mission, membership, date) 방어선
        assertThat(succeeded.get()).isEqualTo(1);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM house_mission_daily_contributions WHERE mission_id = ?",
                Integer.class, missionId);
        assertThat(historyCount).isEqualTo(1);
        Integer contributionValue = jdbcTemplate.queryForObject(
                "SELECT contribution_value FROM house_mission_participants WHERE mission_id = ?",
                Integer.class, missionId);
        assertThat(contributionValue).isEqualTo(1);
    }

    @Test
    void 동시에_claim_해도_성장_포인트는_한_번만_지급된다() throws Exception {
        setUpDailyMission(50);
        houseMissionService.contribute(ownerId, houseId, missionId); // 1/2 = 50% 달성

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Long claimer = (i % 2 == 0) ? ownerId : memberId;
            pool.submit(() -> {
                try {
                    start.await();
                    houseMissionService.claim(claimer, houseId, missionId);
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 하루 1회 초과분은 409 - 실패한 claim 은 포인트를 남기지 않아야 한다(아래 growth_points 검증)
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 핵심 불변식: 보상 1행 + 성장 포인트 정확히 +20 (실패 트랜잭션의 지급분은 롤백)
        assertThat(succeeded.get()).isEqualTo(1);
        Integer rewardCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM house_mission_daily_rewards WHERE mission_id = ?",
                Integer.class, missionId);
        assertThat(rewardCount).isEqualTo(1);
        Integer growthPoints = jdbcTemplate.queryForObject(
                "SELECT growth_points FROM house WHERE id = ?", Integer.class, houseId);
        assertThat(growthPoints).isEqualTo(20);
    }
}
