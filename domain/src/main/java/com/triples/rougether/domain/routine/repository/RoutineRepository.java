package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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
}
