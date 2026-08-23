package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.support.UserMetricCount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineRepository extends JpaRepository<Routine, Long> {

    // 유효기간 판정 기준 타임존
    ZoneId KST = ZoneId.of("Asia/Seoul");

    // 소유권 guard 단건: 타인 소유·미존재·삭제됨 모두 empty
    Optional<Routine> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // 과거 날짜 액션용: 과거 캘린더가 내려준 닫힌(soft-deleted) 버전 id도 소유권만 맞으면 허용
    Optional<Routine> findByIdAndUserId(Long id, Long userId);

    // 기기 캘린더 임포트 중복 판정. soft delete 된 행(스케줄 수정으로 닫힌 옛 버전 포함)도 포함 — unique 와 같은 범위
    boolean existsByUserIdAndExternalSourceAndExternalId(Long userId, String externalSource, String externalId);

    // 응답의 외부 참조 해석용: 계보 원본(origin) row 중 외부 참조가 있는 것만, 소유자 스코프로(soft delete 행 포함)
    List<Routine> findByUserIdAndIdInAndExternalIdIsNotNull(Long userId, Collection<Long> ids);

    // 카테고리 삭제 차단 검사용: status 무관 살아있는 루틴 존재 여부
    boolean existsByCategoryIdAndDeletedAtIsNull(Long categoryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Routine r set r.category = null where r.category.id = :categoryId")
    int clearCategoryByCategoryId(@Param("categoryId") Long categoryId);

    // 미션 삭제 시 전 구성원의 연동 일괄 해제. clearAutomatically 는 쓰지 않는다 - 호출 트랜잭션이
    // 잠근 house/mission 관리 엔티티가 detach 되면 이후·미flush 변경이 유실된다. 대신 이 쿼리 뒤
    // 같은 트랜잭션에서 루틴을 다시 읽지 않는 위치(트랜잭션 끝)에서 호출한다(PC 의 루틴은 stale).
    @Modifying(flushAutomatically = true)
    @Query("update Routine r set r.houseMissionId = null where r.houseMissionId = :missionId")
    int clearHouseMissionLink(@Param("missionId") Long missionId);

    // 탈퇴·강퇴 시 그 집 미션들과의 연동 해제(해당 회원 것만). clearAutomatically 미사용 사유는 위와 동일.
    @Modifying(flushAutomatically = true)
    @Query("update Routine r set r.houseMissionId = null where r.user.id = :userId "
            + "and r.houseMissionId in (select m.id from HouseMission m where m.house.id = :houseId)")
    int clearHouseMissionLinksOfMember(@Param("userId") Long userId, @Param("houseId") Long houseId);

    // 회원탈퇴 시 개인 전용 데이터 일괄 soft delete. 이미 삭제된 루틴의 원래 시각은 보존함.
    // bulk UPDATE 는 auditing 을 우회하므로 updated_at 을 직접 갱신함. clearAutomatically 는 쓰지 않는다
    // - 호출자(탈퇴 트랜잭션) 영속성 컨텍스트를 불필요하게 비우지 않기 위함. 이후 루틴을 다시 읽지 않는 위치에서 호출.
    @Modifying(flushAutomatically = true)
    @Query("update Routine r set r.deletedAt = :now, r.updatedAt = :now "
            + "where r.user.id = :userId and r.deletedAt is null")
    int softDeleteAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    // 리마인드 batch Step1 reader: 지정 분에 예약된 ACTIVE·살아있는 루틴 중 당일 미완료·미발송인 것만 커서 페이징 조회.
    // 반복규칙(요일 등) 판정은 RoutineRecurrence가 processor에서 함(여기서 걸러지지 않음).
    // cursorId 커서(id > cursorId)로 페이징함 - 처리된 루틴이 NOT EXISTS 조건에서 즉시 빠지는 쿼리라
    // offset 기반 페이징은 처리 도중 결과셋이 줄어들며 밀려서 못 읽는 구간이 생김(id는 처리 여부와 무관하게 단조증가)
    // 동거 봇(#308)의 루틴은 리마인드 대상이 아니다(알림 받을 주체 없음).
    @Query("select r from Routine r "
            + "where r.status = :status and r.scheduledTime = :scheduledTime and r.deletedAt is null "
            + "and r.user.bot = false "
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

    // 조정 추천(#329): 계보의 현재 살아있는 버전. 버전 모델상 계보당 살아있는 row 는 최대 1개라는 가정에
    // 기대며(강제 unique 제약은 없음 — 동시 수정 race 로 2개가 되면 이 Optional 조회가 예외로 드러낸다),
    // 추천 수락 경로는 추천 행 락으로 직렬화해 이 가정을 지킨다.
    @Query("select r from Routine r "
            + "where r.user.id = :userId "
            + "and coalesce(r.originRoutineId, r.id) = :originKey "
            + "and r.deletedAt is null")
    Optional<Routine> findAliveByLineage(@Param("userId") Long userId, @Param("originKey") Long originKey);

    // 관리자 추천 퍼널(#332): 여러 계보의 살아있는 버전을 한 번에 조회해 대기/무효 판정의 N+1 을 피한다.
    // 계보 키(루틴 id)는 전역 유일이라 사용자 조건 없이 일괄 조회해도 계보가 섞이지 않는다.
    @Query("select coalesce(r.originRoutineId, r.id) as originKey, r.id as routineId from Routine r "
            + "where coalesce(r.originRoutineId, r.id) in :originKeys and r.deletedAt is null")
    List<LineageAliveVersion> findAliveVersionsByLineages(@Param("originKeys") Collection<Long> originKeys);

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

    @Query("select r.user.id as userId, count(r.id) as metricCount from Routine r "
            + "where r.user.id in :userIds and r.status = :status and r.deletedAt is null "
            + "group by r.user.id")
    List<UserMetricCount> countActiveByUserIds(@Param("userIds") Collection<Long> userIds,
                                               @Param("status") RoutineStatus status);
}
