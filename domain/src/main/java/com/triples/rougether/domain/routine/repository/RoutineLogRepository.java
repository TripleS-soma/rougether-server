package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 과거 캘린더용: 그날 완료 log를 루틴·카테고리까지 fetch. 연관 fetch는 soft-deleted 루틴/카테고리도 포함함
    @Query("select l from RoutineLog l "
            + "join fetch l.routine r "
            + "left join fetch r.category "
            + "where r.user.id = :userId and l.routineDate = :routineDate and l.status = :status")
    List<RoutineLog> findCompletedWithRoutineForDay(
            @Param("userId") Long userId,
            @Param("routineDate") LocalDate routineDate,
            @Param("status") RoutineLogStatus status);
}
