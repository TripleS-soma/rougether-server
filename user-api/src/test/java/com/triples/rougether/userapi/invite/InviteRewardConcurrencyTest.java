package com.triples.rougether.userapi.invite;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.invite.policy.InviteRewardPolicy;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.invite.service.InviteService;
import java.util.ArrayList;
import java.util.List;
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

// 초대 보상의 동시 요청이 이중 지급·한도 우회로 이어지지 않는지 검증.
// 락은 커밋 시점에 풀리므로 @Transactional 테스트로는 검증 불가 — 실제 커밋 + 수동 정리로 검증한다
// (HouseMissionDailyConcurrencyTest 패턴).
//
// 이 테스트가 잡는 실제 결함: 사용자 행 락을 잡아도 판정 조회가 일반 SELECT 면 REPEATABLE READ 스냅샷을
// 읽어 선행 커밋을 못 본다. 판정은 locking read 여야 한다.
@SpringBootTest
class InviteRewardConcurrencyTest {

    @Autowired private InviteService inviteService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM invite_rewards WHERE inviter_user_id = ? OR invitee_user_id = ?",
                    userId, userId);
        }
        for (Long userId : createdUserIds) {
            // redeem 이 재화 원장(wallet_histories, users FK)을 남기므로 users 보다 먼저 지워야 함(#253)
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_invite_codes WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    private User signUp(String email) {
        User user = userRepository.save(User.signUp(email));
        walletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        createdUserIds.add(user.getId());
        return user;
    }

    private int coinBalance(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND currency_type = 'COIN'",
                Integer.class, userId);
    }

    private int runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        AtomicInteger succeeded = new AtomicInteger();

        for (Runnable task : tasks) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 경합에서 밀린 요청 - 본검증은 잔액·이력으로 한다
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        return succeeded.get();
    }

    @Test
    void 같은_사람이_서로_다른_코드를_동시에_써도_보상은_한_번만_지급된다() throws Exception {
        User inviterA = signUp("invite-race-a@rougether.dev");
        User inviterB = signUp("invite-race-b@rougether.dev");
        User invitee = signUp("invite-race-invitee@rougether.dev");
        String codeA = inviteService.getMyCode(inviterA.getId()).code();
        String codeB = inviteService.getMyCode(inviterB.getId()).code();
        int initial = SignupWalletPolicy.INITIAL_COIN_BALANCE;

        int succeeded = runConcurrently(List.of(
                () -> inviteService.redeem(invitee.getId(), codeA),
                () -> inviteService.redeem(invitee.getId(), codeB)));

        assertThat(succeeded).isEqualTo(1);
        assertThat(coinBalance(invitee.getId())).isEqualTo(initial + InviteRewardPolicy.INVITEE_REWARD_COIN);
        Integer rewardRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_rewards WHERE invitee_user_id = ?", Integer.class, invitee.getId());
        assertThat(rewardRows).isEqualTo(1);
        // 밀린 쪽 초대자에게는 코인이 가지 않아야 한다
        int inviterATotal = coinBalance(inviterA.getId());
        int inviterBTotal = coinBalance(inviterB.getId());
        assertThat(inviterATotal + inviterBTotal)
                .isEqualTo(initial * 2 + InviteRewardPolicy.INVITER_REWARD_COIN);
    }

    @Test
    void 한도_경계에서_동시에_써도_초대자_보상이_한도를_넘지_않는다() throws Exception {
        User inviter = signUp("invite-cap-race-inviter@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();
        int max = InviteRewardPolicy.MAX_REWARDED_INVITES_PER_INVITER;
        int initial = SignupWalletPolicy.INITIAL_COIN_BALANCE;

        // 한도 직전(max-1건)까지 채운다
        for (int i = 0; i < max - 1; i++) {
            inviteService.redeem(signUp("invite-cap-race-" + i + "@rougether.dev").getId(), code);
        }

        // 남은 1자리를 두고 두 요청이 동시에 들어온다
        User first = signUp("invite-cap-race-first@rougether.dev");
        User second = signUp("invite-cap-race-second@rougether.dev");
        runConcurrently(List.of(
                () -> inviteService.redeem(first.getId(), code),
                () -> inviteService.redeem(second.getId(), code)));

        // 핵심 불변식: 초대자 누적 보상이 한도(max x 50)를 절대 넘지 않는다
        assertThat(coinBalance(inviter.getId()))
                .isEqualTo(initial + InviteRewardPolicy.INVITER_REWARD_COIN * max);
        Integer rewardedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_rewards WHERE inviter_user_id = ? AND inviter_reward_amount > 0",
                Integer.class, inviter.getId());
        assertThat(rewardedRows).isEqualTo(max);
        // 두 사람 모두 초대받은 몫은 받는다 (한도는 초대자 몫에만 적용)
        assertThat(coinBalance(first.getId())).isEqualTo(initial + InviteRewardPolicy.INVITEE_REWARD_COIN);
        assertThat(coinBalance(second.getId())).isEqualTo(initial + InviteRewardPolicy.INVITEE_REWARD_COIN);
    }
}
