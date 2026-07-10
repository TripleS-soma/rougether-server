package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.PrivacyScope;
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

    // 타인(집 멤버) 열람용: 기간 내 완료 log 중 카테고리 공개 범위가 허용된 것만.
    // 미분류 루틴의 log 는 inner join 으로 자연 제외됨(비공개 취급).
    // 완료 내역은 과거 기록이라 soft-deleted 루틴의 log 도 포함함(캘린더와 동일 원칙)
    @Query("select l from RoutineLog l "
            + "join fetch l.routine r "
            + "join fetch r.category c "
            + "where r.user.id = :userId and l.status = :status "
            + "and l.routineDate between :fromDate and :toDate "
            + "and c.deletedAt is null and c.visibility in :visibilities "
            + "order by l.routineDate desc, l.completedAt desc")
    List<RoutineLog> findVisibleCompletedInPeriod(
            @Param("userId") Long userId,
            @Param("status") RoutineLogStatus status,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("visibilities") List<PrivacyScope> visibilities);

    // 일일 보상 상한: 오늘 지급된 완료 건수(reward_amount > 0)
    @Query("select count(l) from RoutineLog l "
            + "where l.routine.user.id = :userId and l.routineDate = :routineDate "
            + "and l.status = :status and l.rewardAmount > 0")
    long countByRoutine_UserIdAndRoutineDateAndStatusAndRewardAmountGreaterThan(
            @Param("userId") Long userId,
            @Param("routineDate") LocalDate routineDate,
            @Param("status") RoutineLogStatus status);
}
