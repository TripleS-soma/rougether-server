package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

// 봇 자리 양보의 동시성(#309): 만석 집(사람 2 + 봇 2)에 사람 3명이 동시에 참여하면 집 행 락 아래에서
// 봇 2명이 차례로 비켜 2명만 들어오고, 정원(4)은 절대 넘지 않는다. 남는 1명은 HOUSE_FULL.
@SpringBootTest
class BotResidencyConcurrencyTest {

    @Autowired HouseJoinService houseJoinService;
    @Autowired UserRepository userRepository;
    @Autowired UserWalletRepository userWalletRepository;
    @Autowired HouseRepository houseRepository;
    @Autowired HouseMemberRepository houseMemberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private Long houseId;

    @AfterEach
    void cleanup() {
        if (houseId != null) {
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void 만석_집에_사람_셋이_동시에_참여해도_봇이_비켜준_자리만큼만_들어오고_정원을_넘지_않는다() throws Exception {
        User owner = human("race-owner");
        User bot1 = bot("race-bot-1");
        User bot2 = bot("race-bot-2");
        User human2 = human("race-h2");
        String code = ("BR" + UUID.randomUUID().toString().replace("-", "")).substring(0, 8).toUpperCase();
        House house = houseRepository.save(House.create(
                owner, "양보 경합 집", null, null, 4, code, Instant.now().plus(Duration.ofDays(7))));
        houseId = house.getId();
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, bot1, HouseMemberRole.MEMBER));
        houseMemberRepository.save(HouseMember.create(house, bot2, HouseMemberRole.MEMBER));
        houseMemberRepository.save(HouseMember.create(house, human2, HouseMemberRole.MEMBER));
        jdbcTemplate.update("UPDATE house SET current_member_count = 4 WHERE id = ?", houseId);

        List<User> joiners = List.of(human("race-h3"), human("race-h4"), human("race-h5"));
        ExecutorService pool = Executors.newFixedThreadPool(joiners.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(joiners.size());
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger fullRejected = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (User joiner : joiners) {
            pool.submit(() -> {
                try {
                    start.await();
                    houseJoinService.joinByCode(joiner.getId(), code);
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
        assertThat(succeeded.get()).isEqualTo(2);
        assertThat(fullRejected.get()).isEqualTo(1);
        House after = houseRepository.findById(houseId).orElseThrow();
        assertThat(after.getCurrentMemberCount()).isEqualTo(4);
        List<HouseMember> active = houseMemberRepository.findByHouseIdAndStatusWithUser(houseId, HouseMemberStatus.ACTIVE);
        assertThat(active).hasSize(4).allSatisfy(m -> assertThat(m.getUser().isBot()).isFalse());
        assertThat(houseMemberRepository.findByHouseIdAndUserId(houseId, bot1.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);
        assertThat(houseMemberRepository.findByHouseIdAndUserId(houseId, bot2.getId()).orElseThrow().getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);
    }

    private User human(String prefix) {
        User user = userRepository.save(User.signUp(prefix + "-" + UUID.randomUUID() + "@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private User bot(String prefix) {
        User user = userRepository.save(User.bot(
                prefix + "-" + UUID.randomUUID().toString().substring(0, 12), "경합 테스트 봇", null));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }
}
