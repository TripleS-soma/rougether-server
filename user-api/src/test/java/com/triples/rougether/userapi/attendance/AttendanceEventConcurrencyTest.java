package com.triples.rougether.userapi.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.repository.AttendanceCheckInRepository;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class AttendanceEventConcurrencyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 8, 16);

    @Autowired private AttendanceEventService attendanceEventService;
    @Autowired private AttendanceEventRepository attendanceEventRepository;
    @Autowired private AttendanceCheckInRepository attendanceCheckInRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private Clock kstClock;

    private User user;
    private AttendanceEvent event;
    private Theme theme;
    private Item rewardItem;

    @BeforeEach
    void setUp() {
        when(kstClock.getZone()).thenReturn(KST);

        user = userRepository.save(User.signUp("attendance-race@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        theme = themeRepository.save(new Theme(
                "attendance_race", "출석 동시성", "themes/attendance-race.png", true));
        rewardItem = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "출석 동시성 트로피", null, null,
                "items/events/attendance-race-trophy.png", true, true));
        event = attendanceEventRepository.save(AttendanceEvent.create(
                "ATTENDANCE_RACE", "10일 연속 출석", STARTS_ON, STARTS_ON.plusDays(29),
                10, 30, 5, 50, rewardItem));

        for (int day = 0; day < 9; day++) {
            setToday(STARTS_ON.plusDays(day));
            attendanceEventService.checkIn(user.getId());
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM attendance_check_ins WHERE event_id = ?", event.getId());
        jdbcTemplate.update("DELETE FROM attendance_events WHERE id = ?", event.getId());
        jdbcTemplate.update("DELETE FROM user_items WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM items WHERE id = ?", rewardItem.getId());
        jdbcTemplate.update("DELETE FROM themes WHERE id = ?", theme.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    void 열흘째_동시_호출에도_출석_코인_원장_가구는_한_번만_생긴다() throws Exception {
        setToday(STARTS_ON.plusDays(9));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    attendanceEventService.checkIn(user.getId());
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 성공 횟수와 DB 최종 상태로 검증한다.
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(succeeded).hasValue(2);
        assertThat(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), user.getId()))
                .isEqualTo(10);
        assertThat(userItemRepository.findByUserIdAndDeletedAtIsNull(user.getId())).hasSize(1);
        assertThat(userWalletRepository.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN))
                .get()
                .extracting(wallet -> wallet.getBalance())
                .isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 320);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_histories WHERE user_id = ? AND reason = 'ATTENDANCE_REWARD'",
                Integer.class, user.getId())).isEqualTo(10);
    }

    private void setToday(LocalDate date) {
        when(kstClock.instant()).thenReturn(date.atTime(9, 0).atZone(KST).toInstant());
    }
}
