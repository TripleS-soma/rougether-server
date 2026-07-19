package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// 삭제-기여 경합 회귀 — 삭제 트랜잭션이 미션 락을 잡은 동안 시작된 기여는 삭제 커밋 후
// HOUSE_MISSION_NOT_FOUND 로 거부돼야 한다(삭제된 미션에 기여가 기록되는 레이스 차단).
// 락은 커밋 시점에 풀리므로 @Transactional 단일 트랜잭션 테스트로는 검증 불가 — 실제 커밋 + 수동 정리.
@SpringBootTest
class HouseMissionDeleteConcurrencyTest {

    @Autowired private HouseMissionService houseMissionService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseMissionRepository houseMissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long houseId;
    private Long ownerId;
    private Long memberId;
    private Long missionId;

    @AfterEach
    void cleanup() {
        if (missionId != null) {
            houseMissionRepository.deleteById(missionId);
        }
        if (houseId != null) {
            houseMemberRepository.findByHouseIdAndUserId(houseId, ownerId).ifPresent(houseMemberRepository::delete);
            houseMemberRepository.findByHouseIdAndUserId(houseId, memberId).ifPresent(houseMemberRepository::delete);
            houseRepository.deleteById(houseId);
        }
        if (ownerId != null) {
            userRepository.deleteById(ownerId);
        }
        if (memberId != null) {
            userRepository.deleteById(memberId);
        }
    }

    @Test
    void 삭제_트랜잭션이_락을_잡은_동안_시작된_기여는_삭제_후_거부된다() throws Exception {
        User owner = userRepository.save(User.signUp("del-race-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("del-race-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "경합 하우스", null, null, 4, "DRACE234", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
        ownerId = owner.getId();
        memberId = member.getId();
        houseId = house.getId();
        missionId = houseMissionService.create(ownerId, houseId,
                new HouseMissionCreateRequest("경합 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 5, null, null))
                .missionId();

        CountDownLatch deleteLockHeld = new CountDownLatch(1);
        CountDownLatch contributeStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // 스레드1: 삭제 트랜잭션 — 락을 잡은 채 기여 시작을 기다렸다가 커밋 (순서 결정적 재현)
        Future<?> deleter = pool.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
            houseMissionService.delete(ownerId, houseId, missionId);
            deleteLockHeld.countDown();
            try {
                // 기여 스레드가 락 대기에 들어갈 시간을 준 뒤 커밋 → 기여는 삭제 커밋 이후에 미션을 읽는다
                contributeStarted.await(10, TimeUnit.SECONDS);
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // 스레드2: 기여 — 삭제가 락을 잡은 뒤 시작
        Future<?> contributor = pool.submit(() -> {
            try {
                deleteLockHeld.await(10, TimeUnit.SECONDS);
                contributeStarted.countDown();
                assertThatThrownBy(() -> houseMissionService.contribute(memberId, houseId, missionId))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                                .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        deleter.get(30, TimeUnit.SECONDS);
        contributor.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 최종 불변식: 미션은 삭제됐고, 삭제된 미션에 기록된 기여가 없다
        assertThat(houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(missionId, houseId)).isEmpty();
        assertThat(houseMissionService.getMissions(ownerId, houseId).items()).isEmpty();
    }
}
