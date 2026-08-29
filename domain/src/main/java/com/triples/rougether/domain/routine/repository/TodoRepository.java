package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.support.DailyCount;
import com.triples.rougether.domain.support.UserLatestInstant;
import com.triples.rougether.domain.support.UserMetricCount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    // 소유권 guard 단건: 타인 소유·미존재·삭제됨 모두 empty
    Optional<Todo> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 기기 캘린더 임포트 중복 판정. soft delete 된 행도 포함 — 지운 임포트 투두는 되살리지 않음(unique 와 같은 범위)
    boolean existsByUserIdAndExternalSourceAndExternalId(Long userId, String externalSource, String externalId);

    // 카테고리 삭제(UNASSIGN) 미분류 전환. 삭제된 투두는 과거 기록이라 그대로 둠
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Todo t set t.category = null where t.category.id = :categoryId and t.deletedAt is null")
    int clearCategoryByCategoryId(@Param("categoryId") Long categoryId);

    // 카테고리 삭제(PURGE) 일괄 soft delete. 이미 삭제된 투두는 deletedAt을 덮어쓰지 않음
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Todo t set t.deletedAt = :deletedAt "
            + "where t.category.id = :categoryId and t.deletedAt is null")
    int softDeleteByCategoryId(@Param("categoryId") Long categoryId,
                               @Param("deletedAt") Instant deletedAt);

    // 회원탈퇴 시 개인 전용 데이터 일괄 soft delete. 이미 삭제된 투두의 원래 시각은 보존함.
    // bulk UPDATE 는 auditing 을 우회하므로 updated_at 을 직접 갱신함. clearAutomatically 는 쓰지 않는다
    // - 호출자(탈퇴 트랜잭션) 영속성 컨텍스트를 불필요하게 비우지 않기 위함. 이후 투두를 다시 읽지 않는 위치에서 호출.
    @Modifying(flushAutomatically = true)
    @Query("update Todo t set t.deletedAt = :now, t.updatedAt = :now "
            + "where t.user.id = :userId and t.deletedAt is null")
    int softDeleteAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

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

    // 유사도 비교 후보용: 기간 내 마감일이 있는 살아있는 투두 전체(status 무관). 날짜별 그룹핑은 호출자가 한다
    @Query("""
            select t from Todo t
            where t.user.id = :userId
              and t.deletedAt is null
              and t.dueDate between :fromDate and :toDate
            order by t.dueDate asc, t.id asc
            """)
    List<Todo> findOwnedByDueDateBetween(@Param("userId") Long userId,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("toDate") LocalDate toDate);

    // 월 캘린더용: 기간 내 마감일별 살아있는 투두 건수. 소싱 규칙은 findOwnedWithFilters(dueDate)와 동일(마감일 없는 투두 제외)
    @Query("""
            select t.dueDate as targetDate, count(t.id) as itemCount from Todo t
            where t.user.id = :userId
              and t.deletedAt is null
              and t.dueDate between :fromDate and :toDate
            group by t.dueDate
            """)
    List<DailyCount> countOwnedByDueDateBetween(@Param("userId") Long userId,
                                                @Param("fromDate") LocalDate fromDate,
                                                @Param("toDate") LocalDate toDate);

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
              and t.user.bot = false
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

    // 일일 보상 상한: KST 날짜에 완료된 투두로 지급된 코인 합계.
    // 삭제된 투두도 포함함 — 삭제는 코인을 회수하지 않으므로 집계에서 빼면 지급 한도가 부당 복구됨
    @Query("""
            select coalesce(sum(t.rewardAmount), 0) from Todo t
            where t.user.id = :userId
              and t.completedAt >= :kstDayStart and t.completedAt < :kstDayEnd
              and t.status = :status
            """)
    int sumRewardAmountByUserIdAndCompletedAtInKstDay(
            @Param("userId") Long userId,
            @Param("kstDayStart") Instant kstDayStart,
            @Param("kstDayEnd") Instant kstDayEnd,
            @Param("status") TodoStatus status);

    @Query("select t.user.id as userId, count(t.id) as metricCount from Todo t "
            + "where t.user.id in :userIds and t.status = :status and t.completedAt >= :completedAfter "
            + "group by t.user.id")
    List<UserMetricCount> countCompletedByUserIdsSince(
            @Param("userIds") Collection<Long> userIds,
            @Param("status") TodoStatus status,
            @Param("completedAfter") Instant completedAfter);

    // 동거 봇 응원(#310): 사용자별 최근 투두 완료 시각(since 이후).
    @Query("select t.user.id as userId, max(t.completedAt) as latestAt from Todo t "
            + "where t.user.id in :userIds and t.status = :status and t.completedAt >= :since "
            + "group by t.user.id")
    List<UserLatestInstant> findLatestCompletedAtByUserIdsSince(
            @Param("userIds") Collection<Long> userIds,
            @Param("status") TodoStatus status,
            @Param("since") Instant since);

    @Query("""
            select t from Todo t
            where t.user.id = :userId
              and t.status = :status
              and t.dueDate = :targetDate
              and t.deletedAt is null
            order by t.id asc
            """)
    List<Todo> findDailyIncompleteDigestTodoCandidates(@Param("userId") Long userId,
                                                       @Param("targetDate") LocalDate targetDate,
                                                       @Param("status") TodoStatus status);
}
