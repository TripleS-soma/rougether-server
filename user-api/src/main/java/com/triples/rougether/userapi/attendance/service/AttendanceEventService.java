package com.triples.rougether.userapi.attendance.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.attendance.entity.AttendanceCheckIn;
import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.repository.AttendanceCheckInRepository;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.attendance.dto.AttendanceCheckInResponse;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse.Reward;
import com.triples.rougether.userapi.attendance.error.AttendanceErrorCode;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceEventService {

    private final AttendanceEventRepository attendanceEventRepository;
    private final AttendanceCheckInRepository attendanceCheckInRepository;
    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final UserItemRepository userItemRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final Clock kstClock;

    public AttendanceEventStatusResponse getStatus(Long userId) {
        LocalDate today = today();
        return status(requireActiveEvent(today), userId, today);
    }

    @Transactional
    public AttendanceCheckInResponse checkIn(Long userId) {
        Instant now = kstClock.instant();
        LocalDate today = LocalDate.ofInstant(now, kstClock.getZone());
        AttendanceEvent event = requireActiveEvent(today);

        User user = userRepository.findByIdForUpdate(userId)
                .filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));

        if (attendanceCheckInRepository
                .findForUpdateByEventIdAndUserIdAndAttendanceDate(event.getId(), userId, today)
                .isPresent()) {
            return idempotentResponse(event, userId, today);
        }

        AttendanceCheckIn latest = attendanceCheckInRepository
                .findFirstByEventIdAndUserIdOrderByAttendanceDateDesc(event.getId(), userId)
                .orElse(null);
        if (latest != null && latest.isCompleted()) {
            return idempotentResponse(event, userId, today);
        }

        int streakDay = latest != null && latest.getAttendanceDate().equals(today.minusDays(1))
                ? latest.getStreakDay() + 1
                : 1;
        int coinRewardAmount = event.coinRewardFor(streakDay);
        UserWallet coinWallet = userWalletRepository
                .findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseGet(() -> userWalletRepository.save(UserWallet.create(user, CurrencyType.COIN)));
        coinWallet.add(coinRewardAmount);

        AttendanceCheckIn checkIn = AttendanceCheckIn.record(
                event, user, today, streakDay, coinRewardAmount, now);
        boolean rewardGrantedNow = processFurnitureReward(event, user, checkIn, streakDay, now);
        attendanceCheckInRepository.save(checkIn);

        walletHistoryRecorder.record(
                coinWallet,
                coinRewardAmount,
                WalletHistoryReason.ATTENDANCE_REWARD,
                WalletHistory.SOURCE_ATTENDANCE_CHECK_IN,
                checkIn.getId());

        return new AttendanceCheckInResponse(
                true, coinRewardAmount, coinWallet.getBalance(), rewardGrantedNow,
                status(event, userId, today));
    }

    private boolean processFurnitureReward(AttendanceEvent event, User user,
                                           AttendanceCheckIn checkIn, int streakDay, Instant now) {
        if (streakDay != event.getTargetDays()) {
            return false;
        }

        UserItem rewardUserItem = userItemRepository
                .findByUserIdAndItemIdAndDeletedAtIsNull(user.getId(), event.getRewardItem().getId())
                .orElse(null);
        boolean newlyGranted = rewardUserItem == null;
        if (newlyGranted) {
            rewardUserItem = userItemRepository.save(UserItem.create(user, event.getRewardItem()));
        }
        checkIn.processReward(rewardUserItem, newlyGranted, now);
        return newlyGranted;
    }

    private AttendanceCheckInResponse idempotentResponse(
            AttendanceEvent event, Long userId, LocalDate today) {
        return new AttendanceCheckInResponse(
                false, 0, coinBalance(userId), false, status(event, userId, today));
    }

    private AttendanceEventStatusResponse status(AttendanceEvent event, Long userId, LocalDate today) {
        List<AttendanceCheckIn> checkIns = attendanceCheckInRepository
                .findByEventIdAndUserIdOrderByAttendanceDateAsc(event.getId(), userId);
        AttendanceCheckIn latest = checkIns.isEmpty() ? null : checkIns.getLast();
        AttendanceCheckIn completed = checkIns.stream()
                .filter(AttendanceCheckIn::isCompleted)
                .findFirst()
                .orElse(null);

        int currentStreak = currentStreak(event, latest, completed, today);
        Item rewardItem = event.getRewardItem();
        Long rewardUserItemId = completed == null ? null : completed.getRewardUserItem().getId();

        return new AttendanceEventStatusResponse(
                event.getId(), event.getCode(), event.getTitle(), event.getStartsOn(), event.getEndsOn(),
                event.getTargetDays(), currentStreak,
                latest != null && latest.getAttendanceDate().equals(today),
                completed != null,
                checkIns.stream().map(AttendanceCheckIn::getAttendanceDate).toList(),
                dailyRewards(event, currentStreak),
                new Reward(rewardItem.getId(), rewardItem.getName(), rewardItem.getAssetKey(),
                        rewardUserItemId, completed != null));
    }

    private List<AttendanceEventStatusResponse.DailyReward> dailyRewards(
            AttendanceEvent event, int currentStreak) {
        return IntStream.rangeClosed(1, event.getTargetDays())
                .mapToObj(day -> new AttendanceEventStatusResponse.DailyReward(
                        day, event.coinRewardFor(day), day == event.getTargetDays(), day <= currentStreak))
                .toList();
    }

    private int currentStreak(AttendanceEvent event, AttendanceCheckIn latest,
                              AttendanceCheckIn completed, LocalDate today) {
        if (completed != null) {
            return event.getTargetDays();
        }
        if (latest == null || latest.getAttendanceDate().isBefore(today.minusDays(1))) {
            return 0;
        }
        return latest.getStreakDay();
    }

    private int coinBalance(Long userId) {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .map(UserWallet::getBalance)
                .orElse(0);
    }

    private AttendanceEvent requireActiveEvent(LocalDate today) {
        List<AttendanceEvent> events = attendanceEventRepository.findActiveOn(today);
        if (events.isEmpty()) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_EVENT_NOT_FOUND);
        }
        if (events.size() > 1) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_EVENT_CONFIGURATION_INVALID);
        }
        return events.getFirst();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(kstClock.instant(), kstClock.getZone());
    }
}
