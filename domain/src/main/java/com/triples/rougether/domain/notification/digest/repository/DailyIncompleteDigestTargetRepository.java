package com.triples.rougether.domain.notification.digest.repository;

import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTarget;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTargetType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyIncompleteDigestTargetRepository extends JpaRepository<DailyIncompleteDigestTarget, Long> {

    List<DailyIncompleteDigestTarget> findAllByDigestIdOrderByIdAsc(Long digestId);

    @Modifying(flushAutomatically = true)
    @Query("delete from DailyIncompleteDigestTarget t where t.digest.user.id = :userId")
    void deleteAllByDigestUserId(@Param("userId") Long userId);

    @Query("select t.digest.id as digestId, d.sentAt as digestSentAt, l.completedAt as completedAt "
            + "from DailyIncompleteDigestTarget t "
            + "join t.digest d "
            + "join RoutineLog l on l.routineDate = d.digestDate and l.completedAt is not null "
            + "join l.routine r "
            + "where t.targetType = :targetType and d.sentAt is not null "
            + "and d.pushStatus = com.triples.rougether.domain.notification.entity.PushStatus.SENT "
            + "and d.digestDate between :fromDate and :toDate "
            + "and l.status = com.triples.rougether.domain.routine.entity.RoutineLogStatus.COMPLETED "
            + "and coalesce(r.originRoutineId, r.id) = t.targetId "
            // 계보 id 는 사용자별 유일해 결과는 같지만, 사용자 조인을 명시해 선택도와 의도를 분명히 한다.
            + "and r.user = d.user "
            + "and l.completedAt >= d.sentAt "
            + "and l.completedAt < :completedBefore")
    List<DailyDigestTargetCompletionEventRow> findCompletedRoutineTargetsAfterDigestSent(
            @Param("targetType") DailyIncompleteDigestTargetType targetType,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("completedBefore") Instant completedBefore);

    @Query("select t.digest.id as digestId, d.sentAt as digestSentAt, todo.completedAt as completedAt "
            + "from DailyIncompleteDigestTarget t "
            + "join t.digest d "
            + "join Todo todo on todo.id = t.targetId "
            + "where t.targetType = :targetType and d.sentAt is not null "
            + "and d.pushStatus = com.triples.rougether.domain.notification.entity.PushStatus.SENT "
            + "and d.digestDate between :fromDate and :toDate "
            + "and todo.status = com.triples.rougether.domain.routine.entity.TodoStatus.COMPLETED "
            + "and todo.completedAt >= d.sentAt "
            + "and todo.completedAt < :completedBefore")
    List<DailyDigestTargetCompletionEventRow> findCompletedTodoTargetsAfterDigestSent(
            @Param("targetType") DailyIncompleteDigestTargetType targetType,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("completedBefore") Instant completedBefore);
}
