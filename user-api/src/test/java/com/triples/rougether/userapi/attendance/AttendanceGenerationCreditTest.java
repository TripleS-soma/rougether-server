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

@SpringBootTest(properties = {"billing.require-credits=true", "furniture.generation.worker-enabled=false"})
class AttendanceGenerationCreditTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 8, 16);

    @Autowired private AttendanceEventService attendanceEventService;
    @Autowired private AttendanceEventRepository attendanceEventRepository;
    @Autowired private AttendanceCheckInRepository attendanceCheckInRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private UserItemRepository userItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private com.triples.rougether.domain.billing.repository.FurnitureCreditAccountRepository accounts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.triples.rougether.domain.billing.repository.FurnitureCreditEntryRepository entries;
    @Autowired private com.triples.rougether.userapi.furniture.service.FurnitureGenerationTransactions generation;
    @MockitoBean private Clock kstClock;

    private User user;
    private AttendanceEvent event;

    @BeforeEach
    void setUp() {
        when(kstClock.getZone()).thenReturn(KST);

        user = userRepository.save(User.signUp("attendance-race@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        event = attendanceEventRepository.save(AttendanceEvent.createGenerationEvent(
                "ATTENDANCE_CREDIT_RACE", "7일 출석 · 나만의 가구", STARTS_ON, STARTS_ON.plusDays(29),
                30, 5, 50));

        for (int day = 0; day < 6; day++) {
            setToday(STARTS_ON.plusDays(day));
            attendanceEventService.checkIn(user.getId());
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM furniture_credit_entries WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM furniture_credit_reservations WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM furniture_generation_jobs WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM furniture_credit_accounts WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM attendance_check_ins WHERE event_id = ?", event.getId());
        jdbcTemplate.update("DELETE FROM attendance_events WHERE id = ?", event.getId());
        jdbcTemplate.update("DELETE FROM user_items WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", user.getId());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    void 일곱째날_동시출석에도_생성권은_한번만_지급한다() throws Exception {
        setToday(STARTS_ON.plusDays(6));
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
        assertThat(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), user.getId())).isEqualTo(7);
        assertThat(accounts.findById(user.getId()).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(entries.findAll().stream().filter(e -> e.getUserId().equals(user.getId())).count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_items WHERE user_id = ?", Long.class, user.getId())).isZero();
        var status = attendanceEventService.getStatus(user.getId());
        assertThat(status.completed()).isTrue();
        assertThat(status.reward().type()).isEqualTo("GENERATION_CREDIT");
        assertThat(status.reward().itemId()).isNull();
        assertThat(status.dailyRewards().getLast().generationCreditAmount()).isEqualTo(1);
        setToday(STARTS_ON.plusDays(7));
        assertThat(attendanceEventService.checkIn(user.getId()).newCheckIn()).isFalse();
        assertThat(accounts.findById(user.getId()).orElseThrow().getBalance()).isEqualTo(1);
    }

    @Test
    void 생성권_지급실패시_출석과_코인도_롤백한다() {
        setToday(STARTS_ON.plusDays(6));
        org.mockito.Mockito.doThrow(new IllegalStateException("원장 저장 실패"))
                .when(entries).save(org.mockito.ArgumentMatchers.any());
        var before = userWalletRepository.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attendanceEventService.checkIn(user.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), user.getId())).isEqualTo(6);
        assertThat(userWalletRepository.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance()).isEqualTo(before);
        assertThat(accounts.findById(user.getId())).isEmpty();
    }

    @Test
    void 출석생성권으로_접수하고_실패하면_한번만_돌려준다() {
        setToday(STARTS_ON.plusDays(6));
        attendanceEventService.checkIn(user.getId());
        String requestId = java.util.UUID.randomUUID().toString();
        var reservation = generation.reserve(user.getId(), requestId, "digest", "의자");
        assertThat(accounts.findById(user.getId()).orElseThrow().getBalance()).isZero();
        assertThat(accounts.findById(user.getId()).orElseThrow().getReserved()).isEqualTo(1);
        assertThat(generation.reserve(user.getId(), requestId, "digest", "의자").created()).isFalse();
        generation.uploadFailed(user.getId(), reservation.job().id());
        generation.uploadFailed(user.getId(), reservation.job().id());
        assertThat(accounts.findById(user.getId()).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(accounts.findById(user.getId()).orElseThrow().getReserved()).isZero();
    }

    @Test
    void 미완주자는_생성을_접수할수없고_하루빠지면_첫날부터_시작한다() {
        setToday(STARTS_ON.plusDays(7));
        var result = attendanceEventService.checkIn(user.getId());
        assertThat(result.status().currentStreak()).isEqualTo(1);
        assertThat(result.rewardGrantedNow()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                generation.reserve(user.getId(), java.util.UUID.randomUUID().toString(), "digest", "의자"))
                .isInstanceOf(com.triples.rougether.common.error.BusinessException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM furniture_generation_jobs WHERE user_id = ?", Long.class, user.getId())).isZero();
    }

    private void setToday(LocalDate date) {
        when(kstClock.instant()).thenReturn(date.atTime(9, 0).atZone(KST).toInstant());
    }
}
