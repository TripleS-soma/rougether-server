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
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    @Autowired private HouseMemberCommandService houseMemberCommandService;
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

    // 재발급의 dirty flush(전체 컬럼 UPDATE)가 동시 탈퇴의 status/left_at 을 되덮지 않는지 검증.
    // house 락 직렬화가 없으면 탈퇴 커밋 후 재발급 플러시가 ACTIVE 를 복구해 인원수와 어긋난다.
    @Test
    void 재발급과_탈퇴가_경합해도_탈퇴_상태가_유지된다() throws Exception {
        User owner = userRepository.save(User.signUp("reissue-race-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("reissue-race-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "재발급 경합 집", null, null, 4, "RRACE234",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseRepository.save(house);
        ownerId = owner.getId();
        firstApplicantId = member.getId();
        houseId = house.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        pool.submit(() -> {
            try {
                start.await();
                houseCommandService.reissueInviteCode(firstApplicantId, houseId);
            } catch (BusinessException error) {
                // 탈퇴가 먼저 커밋되면 HOUSE_NOT_MEMBER 로 거부되는 것이 정상.
                if (error.getErrorCode() != HouseErrorCode.HOUSE_NOT_MEMBER) {
                    unexpected.add(error);
                }
            } catch (Throwable error) {
                unexpected.add(error);
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                houseMemberCommandService.leave(firstApplicantId, houseId);
            } catch (Throwable error) {
                unexpected.add(error);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected).isEmpty();
        var stored = houseMemberRepository.findByHouseIdAndUserId(houseId, firstApplicantId)
                .orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(HouseMemberStatus.LEFT);
        assertThat(stored.getInviteCode()).isNull();
        assertThat(houseRepository.findById(houseId).orElseThrow().getCurrentMemberCount())
                .isEqualTo(1);
    }

    // 소유권 양도의 role flush(전체 컬럼 UPDATE)가 동시 재발급된 개인 코드를 되덮지 않는지 검증.
    // 양도가 house 락에 참여하지 않으면 재발급 커밋 후 stale flush 가 invite_code 를 null 로 되돌린다.
    @Test
    void 소유권_양도와_재발급이_경합해도_발급된_개인_코드가_유실되지_않는다() throws Exception {
        User owner = userRepository.save(User.signUp("transfer-race-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("transfer-race-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "양도 경합 집", null, null, 4, "TRACE234",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        HouseMember targetMembership = houseMemberRepository.save(
                HouseMember.create(house, member, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseRepository.save(house);
        ownerId = owner.getId();
        firstApplicantId = member.getId();
        houseId = house.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicReference<String> issuedCode = new AtomicReference<>();

        pool.submit(() -> {
            try {
                start.await();
                issuedCode.set(houseCommandService
                        .reissueInviteCode(firstApplicantId, houseId).inviteCode());
            } catch (Throwable error) {
                unexpected.add(error);
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                houseMemberCommandService.transferOwnership(
                        ownerId, houseId, targetMembership.getId());
            } catch (Throwable error) {
                unexpected.add(error);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected).isEmpty();
        var stored = houseMemberRepository.findById(targetMembership.getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(HouseMemberRole.OWNER);
        String houseCode = houseRepository.findById(houseId).orElseThrow().getInviteCode();
        if (issuedCode.get().equals(houseCode)) {
            // 양도가 먼저 커밋된 경우 - 재발급이 소유자 분기로 흘러 집 공용 코드를 회전시킨 것.
            assertThat(stored.getInviteCode()).isNull();
        } else {
            // 재발급이 먼저 커밋된 경우 - 양도 flush 가 개인 코드를 되덮으면 안 된다.
            assertThat(stored.getInviteCode()).isEqualTo(issuedCode.get());
        }
    }
}
