package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.support.DailyCount;
import com.triples.rougether.domain.support.UserMetricCount;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineLogRepository extends JpaRepository<RoutineLog, Long> {

    List<RoutineLog> findByRoutineIdAndRoutineDate(Long routineId, LocalDate routineDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RoutineLog l where l.routine.id in "
            + "(select r.id from Routine r where r.category.id = :categoryId "
            + "  and not exists (select 1 from Routine live "
            + "    where coalesce(live.originRoutineId, live.id) = coalesce(r.originRoutineId, r.id) "
            + "    and live.deletedAt is null)) "
            + "and not (l.routineDate = :today and l.rewardAmount > 0)")
    int deleteByCategoryId(@Param("categoryId") Long categoryId, @Param("today") LocalDate today);

    Optional<RoutineLog> findByRoutineIdAndRoutineDateAndStatus(
            Long routineId, LocalDate routineDate, RoutineLogStatus status);

    @Query("select l from RoutineLog l join l.routine r "
            + "where coalesce(r.originRoutineId, r.id) = :originKey "
            + "and l.routineDate = :routineDate and l.status = :status")
    List<RoutineLog> findByLineageAndDateAndStatus(
            @Param("originKey") Long originKey,
            @Param("routineDate") LocalDate routineDate,
            @Param("status") RoutineLogStatus status);

    // 스트릭 판정용: 유저의 그날 완료 수
    long countByRoutine_UserIdAndRoutineDateAndStatus(
            Long userId, LocalDate routineDate, RoutineLogStatus status);

    // 오늘 현황용: 유저의 그날 완료 log(루틴별 완료 판정)
    List<RoutineLog> findByRoutine_UserIdAndRoutineDateAndStatus(
            Long userId, LocalDate routineDate, RoutineLogStatus status);

    // 과거 캘린더용: 그날 log 전체(COMPLETED+FAILED)를 루틴·카테고리까지 fetch.
    @Query("select l from RoutineLog l "
            + "join fetch l.routine r "
            + "left join fetch r.category "
            + "where r.user.id = :userId and l.routineDate = :routineDate")
    List<RoutineLog> findAllWithRoutineForDay(
            @Param("userId") Long userId,
            @Param("routineDate") LocalDate routineDate);

    // 월 캘린더(그제 이전)용: 기간 내 날짜별 log 건수(COMPLETED+FAILED). 소싱 규칙은 findAllWithRoutineForDay와 동일
    // — soft-deleted 루틴의 log도 포함하고, 로그가 없는 날은 행이 없다(0건 처리는 호출자 몫)
    @Query("select l.routineDate as targetDate, count(l.id) as itemCount from RoutineLog l "
            + "join l.routine r "
            + "where r.user.id = :userId and l.routineDate between :fromDate and :toDate "
            + "group by l.routineDate")
    List<DailyCount> countByUserIdAndRoutineDateBetween(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

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

    // 일일 보상 상한: 오늘 루틴 완료로 지급된 코인 합계
    @Query("select coalesce(sum(l.rewardAmount), 0) from RoutineLog l "
            + "where l.routine.user.id = :userId and l.routineDate = :routineDate "
            + "and l.status = :status")
    int sumRewardAmountByRoutine_UserIdAndRoutineDateAndStatus(
            @Param("userId") Long userId,
            @Param("routineDate") LocalDate routineDate,
            @Param("status") RoutineLogStatus status);

    // 주간 회고 대상 사용자(#286): 기간 내 COMPLETED/FAILED log가 있는 사용자. 탈퇴(익명화) 회원과
    // soft-deleted 루틴의 log는 제외한다(회고 결정값). 동거 봇(#308)도 제외 — 봇 로그로 LLM 을 호출하지 않는다.
    // afterUserId 초과분을 user id 오름차순으로 keyset 순회함.
    @Query("select distinct r.user.id from RoutineLog l join l.routine r join r.user u "
            + "where l.routineDate between :fromDate and :toDate "
            + "and l.status in :statuses and r.deletedAt is null and u.deletedAt is null and u.bot = false "
            + "and r.user.id > :afterUserId "
            + "order by r.user.id")
    List<Long> findUserIdsWithLogsInPeriod(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<RoutineLogStatus> statuses,
            @Param("afterUserId") Long afterUserId,
            Pageable pageable);

    // 관리자 AI 회고 관측: 위 findUserIdsWithLogsInPeriod 와 같은 술어를 batch 실행 시점(cutoff) 기준으로 되짚어
    // 그 주 회고 대상이었던 사용자 수를 센다. cutoff 이후에 만들어진 log, cutoff 이후에 soft-delete 된 루틴·회원은
    // batch 가 봤던 상태 그대로 취급해야 하므로 각각 제외/포함한다. 생성 건수와 비교해 누락률의 분모로 쓴다.
    @Query("select count(distinct r.user.id) from RoutineLog l join l.routine r join r.user u "
            + "where l.routineDate between :fromDate and :toDate "
            + "and l.status in :statuses "
            + "and l.createdAt < :cutoff "
            + "and (r.deletedAt is null or r.deletedAt >= :cutoff) "
            + "and (u.deletedAt is null or u.deletedAt >= :cutoff)")
    long countUsersWithLogsInPeriodAsOf(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<RoutineLogStatus> statuses,
            @Param("cutoff") Instant cutoff);

    // 주간 회고 집계(#286): 사용자의 기간 내 COMPLETED/FAILED log를 루틴·카테고리까지 fetch. soft-deleted 루틴 제외.
    @Query("select l from RoutineLog l "
            + "join fetch l.routine r "
            + "left join fetch r.category "
            + "where r.user.id = :userId and l.routineDate between :fromDate and :toDate "
            + "and l.status in :statuses and r.deletedAt is null "
            + "order by l.routineDate asc, l.id asc")
    List<RoutineLog> findAllWithRoutineInPeriod(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<RoutineLogStatus> statuses);

    @Query("select l.routine.user.id as userId, count(l.id) as metricCount from RoutineLog l "
            + "where l.routine.user.id in :userIds and l.status = :status and l.routineDate >= :fromDate "
            + "group by l.routine.user.id")
    List<UserMetricCount> countCompletedByUserIdsSince(
            @Param("userIds") Collection<Long> userIds,
            @Param("status") RoutineLogStatus status,
            @Param("fromDate") LocalDate fromDate);
}
