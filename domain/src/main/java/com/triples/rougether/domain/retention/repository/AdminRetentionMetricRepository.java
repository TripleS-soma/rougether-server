package com.triples.rougether.domain.retention.repository;

import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 관리자 리텐션 KPI 전용 읽기 저장소. 개인정보나 루틴 본문은 읽지 않고 기간이 제한된 집계 재료만 투영함.
public interface AdminRetentionMetricRepository extends Repository<User, Long> {

    @Query("select u.id as userId, u.createdAt as createdAt from User u "
            + "where u.deletedAt is null and u.bot = false order by u.id")
    List<ActiveUserMetricRow> findActiveUsers();

    @Query("select a.user.id as userId, a.activityDate as activityDate from UserDailyActivity a "
            + "join a.user u where a.activityDate between :fromDate and :toDate "
            + "and u.createdAt >= :cohortCreatedAfter "
            + "and u.deletedAt is null and u.bot = false")
    List<DailyActivityMetricRow> findActivitiesBetween(@Param("fromDate") LocalDate fromDate,
                                                       @Param("toDate") LocalDate toDate,
                                                       @Param("cohortCreatedAfter") Instant cohortCreatedAfter);

    @Query("select l.routine.user.id as userId, l.completedAt as completedAt from RoutineLog l "
            + "join l.routine.user u where l.status = :status "
            + "and l.completedAt >= :fromInclusive and l.completedAt < :toExclusive "
            + "and u.deletedAt is null and u.bot = false")
    List<CompletionEventMetricRow> findCompletionEvents(
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            @Param("status") RoutineLogStatus status);

    default List<CompletionEventMetricRow> findCompletionEvents(Instant fromInclusive, Instant toExclusive) {
        return findCompletionEvents(fromInclusive, toExclusive, RoutineLogStatus.COMPLETED);
    }

    @Query("select l.routine.user.id as userId, "
            + "sum(case when l.status = :completedStatus then 1 else 0 end) as completedCount, "
            + "count(l.id) as totalCount from RoutineLog l join l.routine.user u "
            + "where l.routineDate between :fromDate and :toDate and l.status in :statuses "
            + "and u.deletedAt is null and u.bot = false group by l.routine.user.id")
    List<RoutineOutcomeMetricRow> findRoutineOutcomesBetween(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") List<RoutineLogStatus> statuses,
            @Param("completedStatus") RoutineLogStatus completedStatus);

    default List<RoutineOutcomeMetricRow> findRoutineOutcomesBetween(LocalDate fromDate, LocalDate toDate) {
        return findRoutineOutcomesBetween(fromDate, toDate,
                List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.FAILED), RoutineLogStatus.COMPLETED);
    }

    @Query("select s.user.id as userId, s.currentCount as currentCount, "
            + "s.lastSuccessDate as lastSuccessDate from Streak s join s.user u "
            + "where u.deletedAt is null and u.bot = false")
    List<StreakMetricRow> findStreaksForActiveUsers();

    // 공동군 = 현재 ACTIVE·미삭제 집에서 나 외 ACTIVE 비봇·비탈퇴 사용자가 한 명 이상인 사용자.
    @Query("select distinct hm.user.id from HouseMember hm join hm.house h join hm.user u "
            + "where hm.status = :status and h.deletedAt is null "
            + "and u.deletedAt is null and u.bot = false "
            + "and exists (select peer.id from HouseMember peer join peer.user peerUser "
            + "where peer.house = h and peer.user.id <> hm.user.id and peer.status = :status "
            + "and peerUser.deletedAt is null and peerUser.bot = false)")
    List<Long> findSharedUserIds(@Param("status") HouseMemberStatus status);

    interface ActiveUserMetricRow {
        Long getUserId();

        Instant getCreatedAt();
    }

    interface DailyActivityMetricRow {
        Long getUserId();

        LocalDate getActivityDate();
    }

    interface CompletionEventMetricRow {
        Long getUserId();

        Instant getCompletedAt();
    }

    interface RoutineOutcomeMetricRow {
        Long getUserId();

        long getCompletedCount();

        long getTotalCount();
    }

    interface StreakMetricRow {
        Long getUserId();

        int getCurrentCount();

        LocalDate getLastSuccessDate();
    }
}
