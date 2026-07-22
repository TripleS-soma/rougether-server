package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    // 소유권 guard 단건: 타인 소유·미존재·삭제됨 모두 empty
    Optional<Todo> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // categoryId/status/dueDate는 null이면 해당 조건 무시(동적 필터). dueDate는 오늘 현황용으로도 재사용함
    @Query("""
            select t from Todo t
            where t.user.id = :userId
              and t.deletedAt is null
              and (:categoryId is null or t.category.id = :categoryId)
              and (:status is null or t.status = :status)
              and (:dueDate is null or t.dueDate = :dueDate)
            order by t.dueDate asc, t.id asc
            """)
    List<Todo> findOwnedWithFilters(@Param("userId") Long userId,
                                    @Param("categoryId") Long categoryId,
                                    @Param("status") TodoStatus status,
                                    @Param("dueDate") LocalDate dueDate);

    // 타인(집 멤버) 열람용: 그날 마감 투두 중 카테고리 공개 범위가 허용된 것만.
    // 미분류(category null) 투두는 inner join 으로 자연 제외됨(비공개 취급)
    @Query("""
            select t from Todo t
            join fetch t.category c
            where t.user.id = :userId
              and t.deletedAt is null
              and t.dueDate = :dueDate
              and c.deletedAt is null
              and c.visibility in :visibilities
            order by t.id asc
            """)
    List<Todo> findVisibleDueOn(@Param("userId") Long userId,
                                @Param("dueDate") LocalDate dueDate,
                                @Param("visibilities") List<PrivacyScope> visibilities);

    // 리마인드 batch 투두 reader: 대상일 dueDate·대상 분 dueTime의 PENDING·살아있는 투두 중 당일 미발송만 커서 페이징 조회.
    // dueDate 없는 투두는 dueDate = :date 조건으로 자연 제외됨(알림 대상 아님).
    // RoutineRepository.findReminderCandidates와 같은 이유로 offset 대신 id 커서(id > cursorId) 페이징
    @Query("""
            select t from Todo t
            where t.status = :status
              and t.dueDate = :date
              and t.dueTime = :dueTime
              and t.deletedAt is null
              and t.id > :cursorId
              and not exists (select 1 from Notification n
                where n.user = t.user and n.type = :notificationType and n.refId = t.id
                and n.createdAt >= :dayStart and n.createdAt < :dayEndExclusive)
            order by t.id asc
            """)
    List<Todo> findReminderCandidates(@Param("status") TodoStatus status,
                                      @Param("date") LocalDate date,
                                      @Param("dueTime") LocalTime dueTime,
                                      @Param("notificationType") NotificationType notificationType,
                                      @Param("dayStart") Instant dayStart,
                                      @Param("dayEndExclusive") Instant dayEndExclusive,
                                      @Param("cursorId") Long cursorId,
                                      Pageable pageable);

    // 일일 보상 상한: KST 날짜에 완료되고 지급된 투두 건수(reward_amount > 0).
    // 삭제된 투두도 포함함 — 삭제는 코인을 회수하지 않으므로 집계에서 빼면 지급 슬롯이 부당 복구됨
    @Query("""
            select count(t) from Todo t
            where t.user.id = :userId
              and t.completedAt >= :kstDayStart and t.completedAt < :kstDayEnd
              and t.status = :status
              and t.rewardAmount > 0
            """)
    long countCompletedByUserIdAndCompletedAtInKstDayAndRewardAmountGreaterThan(
            @Param("userId") Long userId,
            @Param("kstDayStart") Instant kstDayStart,
            @Param("kstDayEnd") Instant kstDayEnd,
            @Param("status") TodoStatus status);
}
