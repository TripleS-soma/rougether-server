package com.triples.rougether.userapi.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.repository.AttendanceCheckInRepository;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.attendance.dto.AttendanceCheckInResponse;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AttendanceEventServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 8, 16);

    @Autowired private AttendanceEventService attendanceEventService;
    @Autowired private AttendanceEventRepository attendanceEventRepository;
    @Autowired private AttendanceCheckInRepository attendanceCheckInRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private WalletHistoryRepository walletHistoryRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserItemRepository userItemRepository;

    @MockitoBean private Clock kstClock;

    private User user;
    private AttendanceEvent event;
    private Item rewardItem;

    @BeforeEach
    void setUp() {
        when(kstClock.getZone()).thenReturn(KST);

        user = userRepository.save(User.signUp("attendance-event@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        Theme theme = themeRepository.save(new Theme(
                "attendance_event", "출석 이벤트", "themes/attendance-event.png", true));
        rewardItem = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "10일 출석 기념 트로피", null, null,
                "items/events/attendance-10-day-trophy.png", true, true));
        event = attendanceEventRepository.save(AttendanceEvent.create(
                "ATTENDANCE_10D_2026", "10일 연속 출석", STARTS_ON, STARTS_ON.plusDays(29),
                10, 30, 5, 50, rewardItem));
    }

    @Test
    void 열흘_연속_출석하면_일차별_코인과_마지막_날_가구가_한_번_지급된다() {
        AttendanceCheckInResponse response = null;
        List<Integer> expectedRewards = List.of(30, 30, 30, 30, 50, 30, 30, 30, 30, 30);

        for (int day = 0; day < 10; day++) {
            setToday(STARTS_ON.plusDays(day));
            response = attendanceEventService.checkIn(user.getId());

            assertThat(response.newCheckIn()).isTrue();
            assertThat(response.coinRewardAmount()).isEqualTo(expectedRewards.get(day));
            assertThat(response.status().currentStreak()).isEqualTo(day + 1);
        }

        assertThat(response).isNotNull();
        assertThat(response.rewardGrantedNow()).isTrue();
        assertThat(response.status().completed()).isTrue();
        assertThat(response.status().reward().received()).isTrue();
        assertThat(response.status().reward().userItemId()).isNotNull();
        assertThat(userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(
                user.getId(), rewardItem.getId())).isTrue();
        assertThat(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), user.getId()))
                .isEqualTo(10);
        assertThat(coinBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 320);
        assertThat(response.coinBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 320);

        List<WalletHistory> histories = attendanceHistories();
        assertThat(histories).hasSize(10);
        assertThat(histories).extracting(WalletHistory::getAmount)
                .containsExactlyInAnyOrderElementsOf(expectedRewards);
        assertThat(histories).allSatisfy(history -> {
            assertThat(history.getReason()).isEqualTo(WalletHistoryReason.ATTENDANCE_REWARD);
            assertThat(history.getSourceType()).isEqualTo(WalletHistory.SOURCE_ATTENDANCE_CHECK_IN);
            assertThat(history.getSourceId()).isNotNull();
        });
    }

    @Test
    void 같은_날_재호출하면_코인과_원장을_중복_지급하지_않는다() {
        setToday(STARTS_ON);

        AttendanceCheckInResponse first = attendanceEventService.checkIn(user.getId());
        AttendanceCheckInResponse retried = attendanceEventService.checkIn(user.getId());

        assertThat(first.coinRewardAmount()).isEqualTo(30);
        assertThat(retried.newCheckIn()).isFalse();
        assertThat(retried.coinRewardAmount()).isZero();
        assertThat(retried.coinBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 30);
        assertThat(attendanceHistories()).hasSize(1);
        assertThat(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), user.getId()))
                .isEqualTo(1);
    }

    @Test
    void 하루를_빠뜨리면_다음_출석은_다시_일일차_30코인이다() {
        setToday(STARTS_ON);
        attendanceEventService.checkIn(user.getId());

        setToday(STARTS_ON.plusDays(2));
        AttendanceCheckInResponse response = attendanceEventService.checkIn(user.getId());

        assertThat(response.status().currentStreak()).isEqualTo(1);
        assertThat(response.coinRewardAmount()).isEqualTo(30);
        assertThat(coinBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + 60);
        assertThat(response.status().checkInDates())
                .containsExactly(STARTS_ON, STARTS_ON.plusDays(2));
    }

    @Test
    void 보상_가구를_이미_보유했어도_완료하고_열흘째_30코인은_지급한다() {
        UserItem owned = userItemRepository.save(UserItem.create(user, rewardItem));

        AttendanceCheckInResponse response = null;
        for (int day = 0; day < 10; day++) {
            setToday(STARTS_ON.plusDays(day));
            response = attendanceEventService.checkIn(user.getId());
        }

        assertThat(response).isNotNull();
        assertThat(response.coinRewardAmount()).isEqualTo(30);
        assertThat(response.rewardGrantedNow()).isFalse();
        assertThat(response.status().completed()).isTrue();
        assertThat(response.status().reward().userItemId()).isEqualTo(owned.getId());
        assertThat(userItemRepository.findByUserIdAndDeletedAtIsNull(user.getId())).hasSize(1);
    }

    @Test
    void 상태_조회는_정확한_열흘_보상표를_반환한다() {
        setToday(STARTS_ON);
        attendanceEventService.checkIn(user.getId());

        AttendanceEventStatusResponse response = attendanceEventService.getStatus(user.getId());

        assertThat(response.dailyRewards()).extracting(
                        AttendanceEventStatusResponse.DailyReward::day,
                        AttendanceEventStatusResponse.DailyReward::coinAmount,
                        AttendanceEventStatusResponse.DailyReward::furnitureReward)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 30, false),
                        org.assertj.core.groups.Tuple.tuple(2, 30, false),
                        org.assertj.core.groups.Tuple.tuple(3, 30, false),
                        org.assertj.core.groups.Tuple.tuple(4, 30, false),
                        org.assertj.core.groups.Tuple.tuple(5, 50, false),
                        org.assertj.core.groups.Tuple.tuple(6, 30, false),
                        org.assertj.core.groups.Tuple.tuple(7, 30, false),
                        org.assertj.core.groups.Tuple.tuple(8, 30, false),
                        org.assertj.core.groups.Tuple.tuple(9, 30, false),
                        org.assertj.core.groups.Tuple.tuple(10, 30, true));
    }

    private void setToday(LocalDate date) {
        when(kstClock.instant()).thenReturn(date.atTime(9, 0).atZone(KST).toInstant());
    }

    private int coinBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN)
                .orElseThrow()
                .getBalance();
    }

    private List<WalletHistory> attendanceHistories() {
        return walletHistoryRepository.findAll().stream()
                .filter(history -> history.getUser().getId().equals(user.getId()))
                .filter(history -> history.getReason() == WalletHistoryReason.ATTENDANCE_REWARD)
                .toList();
    }
}
