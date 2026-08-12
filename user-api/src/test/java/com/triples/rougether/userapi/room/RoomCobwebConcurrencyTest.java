package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.userapi.room.service.RoomCobwebService;
import java.sql.Timestamp;
import java.time.Instant;
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

// 거미줄과 지갑을 같은 트랜잭션에서 잠가, 동시 터치가 이중 보상으로 이어지지 않는지 실제 DB로 검증한다.
@SpringBootTest
class RoomCobwebConcurrencyTest {

    @Autowired private RoomCobwebService roomCobwebService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private PersonalRoomRepository personalRoomRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;

    @AfterEach
    void cleanup() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 같은_거미줄을_동시에_두번_눌러도_청소와_코인3개는_한번만_반영된다() throws Exception {
        User user = userRepository.save(User.signUp("room-cobweb-race@rougether.dev"));
        userId = user.getId();
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        personalRoomRepository.save(PersonalRoom.create(user));
        Instant appearedAt = Instant.now().minusSeconds(60);
        jdbcTemplate.update("""
                INSERT INTO room_cobwebs
                    (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                VALUES (?, ?, NULL, NULL, ?)
                """, userId, Timestamp.from(appearedAt), Timestamp.from(appearedAt));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    roomCobwebService.cleanMyRoom(userId);
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 잠금 경합에서 밀린 요청은 COBWEB_NOT_ACTIVE로 끝나야 한다.
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(coinBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_histories WHERE user_id = ? AND reason = 'COBWEB_CLEAN'",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleaned_at IS NOT NULL FROM room_cobwebs WHERE room_user_id = ?",
                Boolean.class, userId)).isTrue();
    }

    private int coinBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND currency_type = 'COIN'",
                Integer.class, userId);
    }
}
