package com.triples.rougether.userapi.attendance.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.userapi.attendance.dto.AttendanceCharacterRewardResponse;
import com.triples.rougether.userapi.global.i18n.CatalogNames;
import com.triples.rougether.domain.attendance.entity.AttendanceCheckIn;
import com.triples.rougether.domain.attendance.entity.AttendanceEvent;
import com.triples.rougether.domain.attendance.entity.AttendanceMode;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
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
    private final UserCharacterRepository userCharacterRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final Clock kstClock;
    private final ChristmasEventPolicy christmasEventPolicy;
    private final com.triples.rougether.userapi.billing.service.FurnitureCreditTransactions credits;

    public AttendanceEventStatusResponse getStatus(Long userId) {
        Instant now = kstClock.instant();
        LocalDate today = LocalDate.ofInstant(now, kstClock.getZone());
        return status(requireActiveEvent(today), userId, today, now);
    }

    public AttendanceEventStatusResponse getStatus(Long userId, Long eventId) {
        Instant now = kstClock.instant();
        return status(requireEvent(eventId), userId, LocalDate.ofInstant(now, kstClock.getZone()), now);
    }

    @Transactional
    public AttendanceCheckInResponse checkIn(Long userId) {
        Instant now = kstClock.instant();
        LocalDate today = LocalDate.ofInstant(now, kstClock.getZone());
        User user = requireUserForUpdate(userId);
        return checkIn(user, requireActiveEvent(today), today, now);
    }

    @Transactional
    public AttendanceCheckInResponse checkIn(Long userId, Long eventId) {
        Instant now = kstClock.instant();
        LocalDate today = LocalDate.ofInstant(now, kstClock.getZone());
        User user = requireUserForUpdate(userId);
        AttendanceEvent event = requireEvent(eventId);
        if (!isActive(event, today, now)) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_EVENT_NOT_ACTIVE);
        }
        return checkIn(user, event, today, now);
    }

    private AttendanceCheckInResponse checkIn(User user, AttendanceEvent event, LocalDate today, Instant now) {
        Long userId = user.getId();
        if (!isActive(event, today, now)) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_EVENT_NOT_ACTIVE);
        }

        if (attendanceCheckInRepository
                .findForUpdateByEventIdAndUserIdAndAttendanceDate(event.getId(), userId, today)
                .isPresent()) {
            return idempotentResponse(event, user, today, now);
        }

        AttendanceCheckIn latest = attendanceCheckInRepository
                .findFirstByEventIdAndUserIdOrderByAttendanceDateDesc(event.getId(), userId)
                .orElse(null);
        if (latest != null && latest.isCompleted()) {
            return idempotentResponse(event, user, today, now);
        }

        int streakDay = latest != null && latest.getAttendanceDate().equals(today.minusDays(1))
                ? latest.getStreakDay() + 1
                : 1;
        int progressDay = event.getMode() == AttendanceMode.CUMULATIVE
                ? Math.toIntExact(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), userId)) + 1
                : streakDay;
        int coinRewardAmount = event.coinRewardFor(progressDay);
        UserWallet coinWallet = userWalletRepository
                .findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseGet(() -> userWalletRepository.save(UserWallet.create(user, CurrencyType.COIN)));
        coinWallet.add(coinRewardAmount);

        AttendanceCheckIn checkIn = AttendanceCheckIn.record(
                event, user, today, streakDay, coinRewardAmount, now);
        boolean rewardGrantedNow = processFurnitureReward(event, user, checkIn, progressDay, now);
        attendanceCheckInRepository.save(checkIn);
        boolean characterGrantedNow = grantCharacterReward(event, user, progressDay);

        walletHistoryRecorder.record(
                coinWallet,
                coinRewardAmount,
                WalletHistoryReason.ATTENDANCE_REWARD,
                WalletHistory.SOURCE_ATTENDANCE_CHECK_IN,
                checkIn.getId());

        return new AttendanceCheckInResponse(
                true, coinRewardAmount, coinWallet.getBalance(), rewardGrantedNow,
                status(event, userId, today, now), characterGrantedNow);
    }

    private boolean processFurnitureReward(AttendanceEvent event, User user,
                                           AttendanceCheckIn checkIn, int streakDay, Instant now) {
        if (streakDay != event.getTargetDays()) {
            return false;
        }

        if (event.getGenerationCreditAmount() > 0) {
            credits.grantAttendance(user.getId(), event.getId());
            checkIn.processGenerationCreditReward(now);
            return true;
        }

        if (event.getRewardItem() == null) {
            checkIn.completeProgress(now);
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
            AttendanceEvent event, User user, LocalDate today, Instant now) {
        boolean characterGrantedNow = grantCharacterReward(event, user,
                Math.toIntExact(attendanceCheckInRepository.countByEventIdAndUserId(event.getId(), user.getId())));
        return new AttendanceCheckInResponse(
                false, 0, coinBalance(user.getId()), false, status(event, user.getId(), today, now), characterGrantedNow);
    }

    // 기존에 3일차를 받은 사용자도 코인/출석을 건드리지 않고 캐릭터만 수령함.
    @Transactional
    public AttendanceCharacterRewardResponse claimCharacterReward(Long userId, Long eventId) {
        Instant now = kstClock.instant();
        LocalDate today = LocalDate.ofInstant(now, kstClock.getZone());
        User user = requireUserForUpdate(userId);
        AttendanceEvent event = requireEvent(eventId);
        if (!isActive(event, today, now)) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_EVENT_NOT_ACTIVE);
        }
        if (event.getRewardCharacter() == null || !event.getRewardCharacter().isActive()) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_CHARACTER_REWARD_NOT_AVAILABLE);
        }
        int progress = Math.toIntExact(attendanceCheckInRepository.countByEventIdAndUserId(eventId, userId));
        if (event.getMode() != AttendanceMode.CUMULATIVE || progress < event.getCharacterRewardDay()) {
            throw new BusinessException(AttendanceErrorCode.ATTENDANCE_CHARACTER_REWARD_LOCKED);
        }
        boolean grantedNow = grantCharacterReward(event, user, progress);
        return new AttendanceCharacterRewardResponse(grantedNow, status(event, userId, today, now));
    }

    // 온보딩, 뽑기, 관리자 지급과 같은 user 행 잠금 안에서 보유 이력까지 검사함.
    private boolean grantCharacterReward(AttendanceEvent event, User user, int progress) {
        Character character = event.getRewardCharacter();
        if (event.getMode() != AttendanceMode.CUMULATIVE || character == null || !character.isActive()
                || progress < event.getCharacterRewardDay()
                || userCharacterRepository.existsByUserIdAndCharacterId(user.getId(), character.getId())) {
            return false;
        }
        userCharacterRepository.save(UserCharacter.create(user, character));
        return true;
    }

    private AttendanceEventStatusResponse.CharacterReward characterReward(
            AttendanceEvent event, Long userId, int progress, boolean active) {
        Character character = event.getRewardCharacter();
        if (character == null || !character.isActive()) return null;
        boolean received = userCharacterRepository.existsByUserIdAndCharacterId(userId, character.getId());
        return new AttendanceEventStatusResponse.CharacterReward(event.getCharacterRewardDay(),
                character.getId(), CatalogNames.name(character), character.getBaseAssetKey(), received,
                active && character.isActive() && progress >= event.getCharacterRewardDay() && !received);
    }

    private AttendanceEventStatusResponse status(AttendanceEvent event, Long userId, LocalDate today, Instant now) {
        List<AttendanceCheckIn> checkIns = attendanceCheckInRepository
                .findByEventIdAndUserIdOrderByAttendanceDateAsc(event.getId(), userId);
        AttendanceCheckIn latest = checkIns.isEmpty() ? null : checkIns.getLast();
        AttendanceCheckIn completed = checkIns.stream()
                .filter(AttendanceCheckIn::isCompleted)
                .findFirst()
                .orElse(null);

        int currentStreak = event.getMode() == AttendanceMode.CUMULATIVE
                ? currentStreak(event, latest, null, today) : currentStreak(event, latest, completed, today);
        int progressDays = event.getMode() == AttendanceMode.CUMULATIVE
                ? Math.min(event.getTargetDays(), checkIns.size()) : currentStreak;
        boolean checkedInToday = latest != null && latest.getAttendanceDate().equals(today);
        boolean active = isActive(event, today, now);
        Item rewardItem = event.getRewardItem();
        Long rewardUserItemId = completed == null || completed.getRewardUserItem() == null ? null : completed.getRewardUserItem().getId();

        return new AttendanceEventStatusResponse(
                event.getId(), event.getCode(), event.getTitle(), event.getStartsOn(), event.getEndsOn(),
                event.getTargetDays(), currentStreak,
                checkedInToday,
                completed != null,
                checkIns.stream().map(AttendanceCheckIn::getAttendanceDate).toList(),
                dailyRewards(event, progressDays),
                event.getGenerationCreditAmount() > 0
                        ? new Reward(null, "AI 가구 생성권", null, null, completed != null,
                                "GENERATION_CREDIT", event.getGenerationCreditAmount())
                        : rewardItem == null
                                ? new Reward(null, null, null, null, false, "NONE", 0)
                                : new Reward(rewardItem.getId(), rewardItem.getName(), rewardItem.getAssetKey(),
                                rewardUserItemId, completed != null),
                event.getMode().name(), progressDays, active, active && completed == null && !checkedInToday,
                characterReward(event, userId, progressDays, active));
    }

    private List<AttendanceEventStatusResponse.DailyReward> dailyRewards(
            AttendanceEvent event, int currentStreak) {
        return IntStream.rangeClosed(1, event.getTargetDays())
                .mapToObj(day -> new AttendanceEventStatusResponse.DailyReward(
                        day, event.coinRewardFor(day), day == event.getTargetDays() && event.getRewardItem() != null,
                        day <= currentStreak, day == event.getTargetDays() ? event.getGenerationCreditAmount() : 0))
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

    private AttendanceEvent requireEvent(Long eventId) {
        return attendanceEventRepository.findWithRewardById(eventId)
                .orElseThrow(() -> new BusinessException(AttendanceErrorCode.ATTENDANCE_EVENT_NOT_FOUND));
    }

    private boolean isActive(AttendanceEvent event, LocalDate today, Instant now) {
        return event.isActiveOn(today)
                && (!ChristmasEventPolicy.CODE.equals(event.getCode()) || christmasEventPolicy.isOpen(now));
    }

    private User requireUserForUpdate(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
    }
}
