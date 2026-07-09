package com.triples.rougether.userapi.routine.reward.service;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DailyRewardService {

    private static final int DAILY_REWARD_CAP = 4;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;

    public DailyRewardService(RoutineLogRepository routineLogRepository,
                              TodoRepository todoRepository) {
        this.routineLogRepository = routineLogRepository;
        this.todoRepository = todoRepository;
    }

    // 오늘 지급 가능 여부 판정
    public boolean canReward(Long userId, LocalDate today) {
        long routineCount = routineLogRepository
                .countByRoutine_UserIdAndRoutineDateAndStatusAndRewardAmountGreaterThan(
                        userId, today, RoutineLogStatus.COMPLETED);

        long todoCount = countTodayTodos(userId, today);

        return (routineCount + todoCount) < DAILY_REWARD_CAP;
    }

    private long countTodayTodos(Long userId, LocalDate today) {
        ZonedDateTime startOfDay = today.atStartOfDay(KST);
        ZonedDateTime endOfDay = today.plusDays(1).atStartOfDay(KST);

        return todoRepository.countCompletedByUserIdAndCompletedAtInKstDayAndRewardAmountGreaterThan(
                userId, startOfDay.toInstant(), endOfDay.toInstant(), TodoStatus.COMPLETED);
    }
}
