package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

    // 리마인드 스케줄러: 지정 분에 예약된 살아있는 ACTIVE 루틴. 반복규칙·완료·중복발송은 호출측이 걸러냄
    List<Routine> findByStatusAndScheduledTimeAndDeletedAtIsNull(RoutineStatus status, LocalTime scheduledTime);

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
