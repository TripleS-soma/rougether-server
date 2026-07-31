package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

// 마지막 한 자리에 대한 동시 수락이 정원을 넘기지 않는지 실제 커밋 트랜잭션으로 검증함.
@SpringBootTest
class HouseJoinRequestConcurrencyTest {

    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseJoinRequestRepository houseJoinRequestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long houseId;
    private Long ownerId;
    private Long firstApplicantId;
    private Long secondApplicantId;

    @AfterEach
    void cleanup() {
        for (Long userId : new Long[]{ownerId, firstApplicantId, secondApplicantId}) {
            if (userId != null) {
                jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            }
        }
        if (houseId != null) {
            jdbcTemplate.update("DELETE FROM house_join_requests WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        }
        for (Long userId : new Long[]{ownerId, firstApplicantId, secondApplicantId}) {
            if (userId != null) {
                userRepository.deleteById(userId);
            }
        }
    }

    @Test
    void 마지막_한_자리에_두_신청을_동시_수락해도_한_명만_가입한다() throws Exception {
        User owner = userRepository.save(User.signUp("join-race-owner@rougether.dev"));
        User first = userRepository.save(User.signUp("join-race-first@rougether.dev"));
        User second = userRepository.save(User.signUp("join-race-second@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "입주 경합 집", null, null, 2, "JRACE234",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        ownerId = owner.getId();
        firstApplicantId = first.getId();
        secondApplicantId = second.getId();
        houseId = house.getId();

        Long firstRequestId = houseJoinService.requestJoin(firstApplicantId, houseId).requestId();
        Long secondRequestId = houseJoinService.requestJoin(secondApplicantId, houseId).requestId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger fullRejected = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (Long requestId : List.of(firstRequestId, secondRequestId)) {
            pool.submit(() -> {
                try {
                    start.await();
                    houseJoinService.acceptRequest(ownerId, houseId, requestId);
                    succeeded.incrementAndGet();
                } catch (BusinessException error) {
                    if (error.getErrorCode() == HouseErrorCode.HOUSE_FULL) {
                        fullRejected.incrementAndGet();
                    } else {
                        unexpected.add(error);
                    }
                } catch (Throwable error) {
                    unexpected.add(error);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(fullRejected.get()).isEqualTo(1);
        assertThat(houseMemberRepository.countByHouseIdAndStatus(houseId, HouseMemberStatus.ACTIVE))
                .isEqualTo(2);
        assertThat(houseRepository.findById(houseId).orElseThrow().getCurrentMemberCount())
                .isEqualTo(2);
    }

    // 개인 초대코드 참여의 스냅샷 경합 방어 검증 - 신청 판정이 house 락 이후 current read 가 아니면
    // 늦은 쪽이 앞선 커밋의 신청 row 를 못 보고 uq_house_join_request 충돌(500)이 난다.
    @Test
    void 같은_개인_코드로_동시_참여해도_신청은_하나만_생긴다() throws Exception {
        User owner = userRepository.save(User.signUp("invite-race-owner@rougether.dev"));
        User inviter = userRepository.save(User.signUp("invite-race-inviter@rougether.dev"));
        User joiner = userRepository.save(User.signUp("invite-race-joiner@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "개인코드 경합 집", null, null, 4, "IRACE234",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, inviter, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseRepository.save(house);
        ownerId = owner.getId();
        firstApplicantId = inviter.getId();
        secondApplicantId = joiner.getId();
        houseId = house.getId();

        String personalCode = houseCommandService
                .reissueInviteCode(inviter.getId(), houseId).inviteCode();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger pendingCreated = new AtomicInteger();
        AtomicInteger alreadyPending = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var response = houseJoinService.joinByCode(secondApplicantId, personalCode);
                    if (response.pendingApproval()) {
                        pendingCreated.incrementAndGet();
                    }
                } catch (BusinessException error) {
                    if (error.getErrorCode() == HouseErrorCode.HOUSE_JOIN_REQUEST_ALREADY_PENDING) {
                        alreadyPending.incrementAndGet();
                    } else {
                        unexpected.add(error);
                    }
                } catch (Throwable error) {
                    unexpected.add(error);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected).isEmpty();
        assertThat(pendingCreated.get()).isEqualTo(1);
        assertThat(alreadyPending.get()).isEqualTo(1);
        assertThat(houseJoinRequestRepository
                .findByHouseIdAndUserId(houseId, secondApplicantId).orElseThrow().getStatus())
                .isEqualTo(HouseJoinRequestStatus.PENDING);
        assertThat(houseMemberRepository.findByHouseIdAndUserId(houseId, secondApplicantId))
                .isEmpty();
        assertThat(houseRepository.findById(houseId).orElseThrow().getCurrentMemberCount())
                .isEqualTo(2);
    }
}
