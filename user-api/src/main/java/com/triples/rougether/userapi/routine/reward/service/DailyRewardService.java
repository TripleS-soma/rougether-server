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

    private static final int DAILY_REWARD_COIN_CAP = 50;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;

    public DailyRewardService(RoutineLogRepository routineLogRepository,
                              TodoRepository todoRepository) {
        this.routineLogRepository = routineLogRepository;
        this.todoRepository = todoRepository;
    }

    public int remainingReward(Long userId, LocalDate today) {
        int routineReward = routineLogRepository
                .sumRewardAmountByRoutine_UserIdAndRoutineDateAndStatus(
                        userId, today, RoutineLogStatus.COMPLETED);

        int todoReward = sumTodayTodoReward(userId, today);

        return Math.max(0, DAILY_REWARD_COIN_CAP - (routineReward + todoReward));
    }

    private int sumTodayTodoReward(Long userId, LocalDate today) {
        ZonedDateTime startOfDay = today.atStartOfDay(KST);
        ZonedDateTime endOfDay = today.plusDays(1).atStartOfDay(KST);

        return todoRepository.sumRewardAmountByUserIdAndCompletedAtInKstDay(
                userId, startOfDay.toInstant(), endOfDay.toInstant(), TodoStatus.COMPLETED);
    }
}
