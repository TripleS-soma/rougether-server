package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineRepository extends JpaRepository<Routine, Long> {

    // 유효기간 판정 기준 타임존
    ZoneId KST = ZoneId.of("Asia/Seoul");

    // 소유권 guard 단건: 타인 소유·미존재·삭제됨 모두 empty
    Optional<Routine> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 카테고리 삭제 차단 검사용: status 무관 살아있는 루틴 존재 여부
    boolean existsByCategoryIdAndDeletedAtIsNull(Long categoryId);

    // 리마인드 batch Step1 reader: 지정 분에 예약된 ACTIVE·살아있는 루틴 중 당일 미완료·미발송인 것만 커서 페이징 조회.
    // 반복규칙(요일 등) 판정은 RoutineRecurrence가 processor에서 함(여기서 걸러지지 않음).
    // cursorId 커서(id > cursorId)로 페이징함 - 처리된 루틴이 NOT EXISTS 조건에서 즉시 빠지는 쿼리라
    // offset 기반 페이징은 처리 도중 결과셋이 줄어들며 밀려서 못 읽는 구간이 생김(id는 처리 여부와 무관하게 단조증가)
    @Query("select r from Routine r "
            + "where r.status = :status and r.scheduledTime = :scheduledTime and r.deletedAt is null "
            + "and r.id > :cursorId "
            + "and not exists (select 1 from RoutineLog l "
            + "  where l.routine = r and l.routineDate = :date and l.status = :completedStatus) "
            + "and not exists (select 1 from Notification n "
            + "  where n.user = r.user and n.type = :notificationType and n.refId = r.id "
            + "  and n.createdAt >= :dayStart and n.createdAt < :dayEndExclusive) "
            + "order by r.id asc")
    List<Routine> findReminderCandidates(@Param("status") RoutineStatus status,
                                         @Param("scheduledTime") LocalTime scheduledTime,
                                         @Param("date") LocalDate date,
                                         @Param("completedStatus") RoutineLogStatus completedStatus,
                                         @Param("notificationType") NotificationType notificationType,
                                         @Param("dayStart") Instant dayStart,
                                         @Param("dayEndExclusive") Instant dayEndExclusive,
                                         @Param("cursorId") Long cursorId,
                                         Pageable pageable);

    List<Routine> findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(Long userId);

    List<Routine> findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
            Long userId, RoutineStatus status);

    List<Routine> findByUserIdAndCategoryIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
            Long userId, Long categoryId);

    List<Routine> findByUserIdAndCategoryIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
            Long userId, Long categoryId, RoutineStatus status);

    // 과거 date에 유효했던 버전 조회
    default List<Routine> findEffectiveOnDay(Long userId, LocalDate date) {
        Instant dayEndExclusive = date.plusDays(1).atStartOfDay(KST).toInstant();
        return findEffectiveBefore(userId, dayEndExclusive);
    }

    @Query("select r from Routine r "
            + "left join fetch r.category "
            + "where r.user.id = :userId "
            + "and r.createdAt < :dayEndExclusive "
            + "and (r.deletedAt is null or r.deletedAt >= :dayEndExclusive)")
    List<Routine> findEffectiveBefore(@Param("userId") Long userId,
                                      @Param("dayEndExclusive") Instant dayEndExclusive);

    @Query("select r from Routine r "
            + "where r.id > :afterId "
            + "and r.createdAt < :dayEndExclusive "
            + "and (r.deletedAt is null or r.deletedAt >= :dayEndExclusive) "
            + "and not exists (select 1 from RoutineLog l "
            + "where coalesce(l.routine.originRoutineId, l.routine.id) = coalesce(r.originRoutineId, r.id) "
            + "and l.routineDate = :routineDate) "
            + "order by r.id asc")
    List<Routine> findDayEndFailCandidates(@Param("afterId") long afterId,
                                           @Param("dayEndExclusive") Instant dayEndExclusive,
                                           @Param("routineDate") LocalDate routineDate,
                                           Pageable pageable);

    // 타인(집 멤버) 열람용: 카테고리 공개 범위가 허용된 루틴만.
    // 미분류(category null) 루틴은 공개 범위를 정할 수 없어 inner join 으로 자연 제외됨(비공개 취급)
    @Query("select r from Routine r "
            + "join fetch r.category c "
            + "where r.user.id = :userId and r.status = :status "
            + "and r.deletedAt is null and c.deletedAt is null "
            + "and c.visibility in :visibilities "
            + "order by r.scheduledTime asc, r.originRoutineId asc")
    List<Routine> findVisibleByUserIdAndStatus(@Param("userId") Long userId,
                                               @Param("status") RoutineStatus status,
                                               @Param("visibilities") List<PrivacyScope> visibilities);
}
