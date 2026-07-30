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
import com.triples.rougether.userapi.house.error.HouseErrorCode;
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
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
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
}
