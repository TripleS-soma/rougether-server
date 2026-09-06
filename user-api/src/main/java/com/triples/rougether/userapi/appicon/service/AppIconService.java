package com.triples.rougether.userapi.appicon.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.appicon.AppIconPolicy;
import com.triples.rougether.domain.appicon.AppIconState;
import com.triples.rougether.domain.appicon.entity.UserAppActivity;
import com.triples.rougether.domain.appicon.repository.UserAppActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.appicon.dto.AppIconResponse;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppIconService {

    private final UserRepository userRepository;
    private final UserAppActivityRepository activityRepository;
    private final StreakRepository streakRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final Clock clock;

    public AppIconResponse get(Long userId) {
        requireHuman(userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND)));
        return evaluate(userId, clock.instant());
    }

    @Transactional
    public AppIconResponse recordForeground(Long userId) {
        User user = requireHuman(userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND)));
        // 잠금 획득 뒤 시각을 정해 동시 요청의 대기로 인한 과거 시각 기록을 줄임.
        Instant now = clock.instant();
        UserAppActivity activity = activityRepository.findById(userId)
                .orElseGet(() -> activityRepository.save(UserAppActivity.start(user, now)));
        activity.recordForeground(now);
        return evaluate(userId, now);
    }

    private AppIconResponse evaluate(Long userId, Instant now) {
        Instant lastForegroundAt = activityRepository.findById(userId)
                .map(UserAppActivity::getLastForegroundAt).orElse(null);
        LocalDate today = now.atZone(AppIconPolicy.KST).toLocalDate();
        int currentStreak = streakRepository.findByUserId(userId)
                .map(streak -> streak.currentCountOn(today)).orElse(0);
        boolean completedToday = routineLogRepository.countByRoutine_UserIdAndRoutineDateAndStatus(
                userId, today, RoutineLogStatus.COMPLETED) > 0
                || todoRepository.existsByUserIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        userId, TodoStatus.COMPLETED,
                        today.atStartOfDay(AppIconPolicy.KST).toInstant(),
                        today.plusDays(1).atStartOfDay(AppIconPolicy.KST).toInstant());
        AppIconState state = AppIconPolicy.evaluate(lastForegroundAt, currentStreak, completedToday, now);
        return new AppIconResponse(state, state.message(), now, lastForegroundAt,
                AppIconPolicy.nextEvaluationAt(state, lastForegroundAt, now), currentStreak, completedToday);
    }

    private User requireHuman(User user) {
        if (user.isDeleted() || user.isBot()) {
            throw new BusinessException(MemberErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}
