package com.triples.rougether.userapi.christmas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.attendance.repository.AttendanceCheckInRepository;
import com.triples.rougether.domain.christmas.repository.EventPopupDismissalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.*;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.infra.fcm.FcmDelivery;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import com.triples.rougether.userapi.christmas.service.ChristmasEventService;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:christmas-attendance;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false", "christmas.event.enabled=true", "firebase.credentials-path=",
        "spring.datasource.hikari.maximum-pool-size=4"})
class ChristmasAttendanceIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate START = LocalDate.of(2026, 11, 9);
    @Autowired AttendanceEventService attendance;
    @Autowired ChristmasEventService christmas;
    @Autowired com.triples.rougether.userapi.member.service.MemberWithdrawalService withdrawal;
    @Autowired AttendanceEventRepository events;
    @Autowired AttendanceCheckInRepository checkIns;
    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired EventPopupDismissalRepository dismissals;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock kstClock;
    @MockitoBean FcmDelivery fcm;
    @MockitoSpyBean WalletHistoryRepository history;
    @MockitoSpyBean ChristmasEventPolicy policy;
    private User user;
    private AttendanceEvent event;

    @BeforeEach void setup() {
        doReturn(KST).when(kstClock).getZone();
        today(START);
        user = users.save(User.signUp(UUID.randomUUID() + "@christmas.test"));
        wallets.saveAll(SignupWalletPolicy.issueAll(user));
        event = events.save(AttendanceEvent.createCumulativeCoins(ChristmasEventPolicy.CODE,
                "크리스마스", START, LocalDate.of(2026, 12, 25), 30, 30, 5, 60));
    }

    @AfterEach void cleanup() {
        reset(history);
        jdbc.update("DELETE FROM wallet_histories WHERE user_id=?", user.getId());
        if (event != null) {
            jdbc.update("DELETE FROM attendance_check_ins WHERE event_id=?", event.getId());
            jdbc.update("DELETE FROM attendance_events WHERE id=?", event.getId());
        }
        jdbc.update("DELETE FROM event_popup_dismissals");
        jdbc.update("DELETE FROM user_wallets WHERE user_id=?", user.getId());
        jdbc.update("DELETE FROM users WHERE id=?", user.getId());
        jdbc.update("DELETE FROM users WHERE email LIKE '%@christmas.test'");
        verifyNoInteractions(fcm);
    }

    @Test void 누적30회는_결석후에도_진척을_유지하고_1080코인만_지급한다() {
        for (int day=0; day<30; day++) {
            today(START.plusDays(day + (day >= 4 ? 2 : 0)));
            var response = attendance.checkIn(user.getId(), event.getId());
            assertThat(response.coinRewardAmount()).isEqualTo((day+1)%5==0 ? 60 : 30);
            assertThat(response.status().progressDays()).isEqualTo(day+1);
            assertThat(response.status().mode()).isEqualTo("CUMULATIVE");
            assertThat(response.rewardGrantedNow()).isFalse();
            assertThat(response.status().reward().type()).isEqualTo("NONE");
            assertThat(response.status().reward().received()).isFalse();
            if (day==4) assertThat(response.status().currentStreak()).isEqualTo(1);
        }
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+1080);
        assertThat(count("wallet_histories")).isEqualTo(30);
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(30);
        var complete=attendance.getStatus(user.getId(),event.getId());
        assertThat(complete.completed()).isTrue();
        assertThat(complete.availableToday()).isFalse();
        today(START.plusDays(33));
        assertThat(attendance.checkIn(user.getId(),event.getId()).coinRewardAmount()).isZero();
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(30);
    }

    @Test void 당일재시도와_응답유실후재시도는_추가지급하지않는다() {
        assertThat(attendance.checkIn(user.getId(),event.getId()).newCheckIn()).isTrue();
        var retry=attendance.checkIn(user.getId(),event.getId());
        assertThat(retry.newCheckIn()).isFalse();
        assertThat(retry.coinRewardAmount()).isZero();
        assertThat(retry.status().checkedInToday()).isTrue();
        assertThat(retry.status().availableToday()).isFalse();
        assertThat(count("wallet_histories")).isEqualTo(1);
    }

    @Test void 종료일KST경계에서_GET이력은가능하고_종료후POST는거부한다() {
        doReturn(Instant.parse("2026-12-25T14:59:59.999999Z")).when(kstClock).instant();
        assertThat(attendance.checkIn(user.getId(),event.getId()).newCheckIn()).isTrue();
        doReturn(Instant.parse("2026-12-25T15:00:00Z")).when(kstClock).instant();
        assertThat(attendance.getStatus(user.getId(),event.getId()).active()).isFalse();
        assertThat(attendance.getStatus(user.getId(),event.getId()).progressDays()).isEqualTo(1);
        assertThatThrownBy(()->attendance.checkIn(user.getId(),event.getId()))
                .hasMessageContaining("출석 이벤트 참여 기간");
        assertThat(count("wallet_histories")).isEqualTo(1);
    }

    @Test void 원장저장실패는_출석과코인을롤백하고_재시도는한번지급한다() {
        doThrow(new IllegalStateException("forced history failure")).when(history).save(any());
        assertThatThrownBy(()->attendance.checkIn(user.getId(),event.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isZero();
        reset(history);
        assertThat(attendance.checkIn(user.getId(),event.getId()).coinRewardAmount()).isEqualTo(30);
        assertThat(count("wallet_histories")).isEqualTo(1);
    }

    @Test void 마지막회차의원장실패는_60코인과완료진척을함께롤백한다() {
        for(int day=0;day<29;day++) { today(START.plusDays(day)); attendance.checkIn(user.getId(),event.getId()); }
        today(START.plusDays(29));
        doThrow(new IllegalStateException("forced completion failure")).when(history).save(any());
        assertThatThrownBy(()->attendance.checkIn(user.getId(),event.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+1020);
        assertThat(attendance.getStatus(user.getId(),event.getId()).completed()).isFalse();
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(29);
        reset(history);
        assertThat(attendance.checkIn(user.getId(),event.getId()).coinRewardAmount()).isEqualTo(60);
        assertThat(attendance.getStatus(user.getId(),event.getId()).completed()).isTrue();
        assertThat(count("wallet_histories")).isEqualTo(30);
    }

    @Test void 완료회차동시요청도_코인과진척과원장은한번만생긴다() throws Exception {
        for(int day=0;day<29;day++) { today(START.plusDays(day)); attendance.checkIn(user.getId(),event.getId()); }
        today(START.plusDays(29));
        var responses=race(()->attendance.checkIn(user.getId(),event.getId()));
        assertThat(responses).filteredOn(r->r.newCheckIn()).hasSize(1);
        assertThat(balance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE+1080);
        assertThat(count("wallet_histories")).isEqualTo(30);
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isEqualTo(30);
    }

    @Test void 팝업숨김은_동시재시도에도계정이격리되고_출석을막지않는다() throws Exception {
        assertThat(christmas.get(user.getId()).active()).isFalse(); // 뽑기 미준비는 all-ready 아님.
        race(()->{christmas.dismissPopup(user.getId()); return true;});
        christmas.dismissPopup(user.getId());
        assertThat(dismissals.count()).isEqualTo(1);
        assertThat(christmas.get(user.getId()).popupDismissed()).isTrue();
        User other=users.save(User.signUp(UUID.randomUUID()+"@christmas.test"));
        assertThat(christmas.get(other.getId()).popupDismissed()).isFalse();
        assertThat(attendance.checkIn(user.getId(),event.getId()).newCheckIn()).isTrue();
    }

    @Test void 실제회원탈퇴는_그계정의팝업숨김만파기한다() {
        User other=users.save(User.signUp(UUID.randomUUID()+"@christmas.test"));
        christmas.dismissPopup(user.getId());
        christmas.dismissPopup(other.getId());
        withdrawal.withdraw(user.getId());
        assertThat(users.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(dismissals.existsByUserIdAndEventCode(user.getId(),ChristmasEventPolicy.CODE)).isFalse();
        assertThat(dismissals.existsByUserIdAndEventCode(other.getId(),ChristmasEventPolicy.CODE)).isTrue();
    }

    @Test void flagOFF는_메타와신규출석을차단하되_GET이력을허용한다() {
        doReturn(false).when(policy).isOpen(any());
        assertThat(christmas.get(user.getId()).active()).isFalse();
        assertThat(attendance.getStatus(user.getId(),event.getId()).active()).isFalse();
        assertThatThrownBy(()->attendance.checkIn(user.getId(),event.getId()))
                .hasMessageContaining("출석 이벤트 참여 기간");
        assertThat(checkIns.countByEventIdAndUserId(event.getId(),user.getId())).isZero();
    }

    @Test void 요청중자정이되어도_한번캡처한시각으로_출석과상태를일치시킨다() {
        doReturn(Instant.parse("2026-11-09T14:59:59.999999Z"), Instant.parse("2026-11-09T15:00:00Z"))
                .when(kstClock).instant();
        clearInvocations(kstClock);
        var response=attendance.checkIn(user.getId(),event.getId());
        assertThat(response.status().checkInDates()).containsExactly(START);
        assertThat(response.status().checkedInToday()).isTrue();
        verify(kstClock,times(1)).instant();
    }

    @Test void 누적이벤트는_기존STREAK조회와기간중복정책에서제외한다() {
        assertThat(events.findActiveOn(START)).isEmpty();
        assertThat(events.countActiveOverlapping(START,START.plusDays(6))).isZero();
        assertThatThrownBy(()->attendance.getStatus(user.getId())).hasMessageContaining("출석 이벤트가 없습니다");
    }

    private <T> List<T> race(Callable<T> action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            Callable<T> task=()->{ready.countDown();start.await();return action.call();};
            var a=pool.submit(task);var b=pool.submit(task);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }
    private void today(LocalDate day) {doReturn(day.atTime(9,0).atZone(KST).toInstant()).when(kstClock).instant();}
    private int balance(){return wallets.findByUserIdAndCurrencyType(user.getId(),CurrencyType.COIN).orElseThrow().getBalance();}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,user.getId());}
}
