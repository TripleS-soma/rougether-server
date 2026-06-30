package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineLogRepository extends JpaRepository<RoutineLog, Long> {

    List<RoutineLog> findByRoutineIdAndRoutineDate(Long routineId, LocalDate routineDate);

    Optional<RoutineLog> findByRoutineIdAndRoutineDateAndStatus(
            Long routineId, LocalDate routineDate, RoutineLogStatus status);

    // 당일 중복 완료 guard
    boolean existsByRoutineIdAndRoutineDateAndStatus(
            Long routineId, LocalDate routineDate, RoutineLogStatus status);

    // 스트릭 판정용: 유저의 그날 완료 수
    long countByRoutine_UserIdAndRoutineDateAndStatus(
            Long userId, LocalDate routineDate, RoutineLogStatus status);

    // 오늘 현황용: 유저의 그날 완료 log(루틴별 완료 판정)
    List<RoutineLog> findByRoutine_UserIdAndRoutineDateAndStatus(
            Long userId, LocalDate routineDate, RoutineLogStatus status);
}
