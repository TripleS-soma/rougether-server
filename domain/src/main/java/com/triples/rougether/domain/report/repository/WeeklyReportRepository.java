package com.triples.rougether.domain.report.repository;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    boolean existsByUserIdAndWeekStartDate(Long userId, LocalDate weekStartDate);

    List<WeeklyReport> findByUserIdOrderByWeekStartDateDesc(Long userId);

    Optional<WeeklyReport> findByIdAndUserId(Long id, Long userId);

    // 주간 회고 push batch reader: 해당 주의 회고(GENERATED·FALLBACK 모두) 중 아직 알림이 없는 것만 조회.
    // 중복 판정은 type + refId(=회고 id, 전역 유일) 존재 여부. 탈퇴(soft delete) 회원의 purge 대기 회고는 제외.
    // RoutineRepository.findReminderCandidates 와 같은 이유로 offset 대신 id 커서(id > cursorId) 페이징 -
    // 이번 job 이 notification 에 insert 하면 not exists 필터로 결과셋이 줄어 offset 이면 뒤 구간이 스킵됨
    // user 는 fetch join — processor 가 회고마다 report.getUser() 로 알림을 만들므로 건별 lazy 조회(N+1)를 막는다
    @Query("select w from WeeklyReport w "
            + "join fetch w.user u "
            + "where w.weekStartDate = :weekStartDate "
            + "and u.deletedAt is null "
            + "and w.id > :cursorId "
            + "and not exists (select 1 from Notification n "
            + "  where n.type = :notificationType and n.refId = w.id) "
            + "order by w.id asc")
    List<WeeklyReport> findPushCandidates(@Param("weekStartDate") LocalDate weekStartDate,
                                          @Param("notificationType") NotificationType notificationType,
                                          @Param("cursorId") Long cursorId,
                                          Pageable pageable);

    // 관리자 AI 회고 관측: 주차 범위의 회고를 주차·상태·모델별 건수로 집계. 탈퇴 회원 회고는 하드 파기되므로 별도 제외 없음.
    // viewedCount 는 count(viewed_at) - null(미열람)을 세지 않아 그룹 내 열람 건수가 된다(#332).
    @Query("select w.weekStartDate as weekStartDate, w.status as status, w.model as model, "
            + "count(w) as reportCount, count(w.viewedAt) as viewedCount, max(w.generatedAt) as lastGeneratedAt "
            + "from WeeklyReport w "
            + "where w.weekStartDate between :fromWeekStartDate and :toWeekStartDate "
            + "group by w.weekStartDate, w.status, w.model "
            + "order by w.weekStartDate desc")
    List<WeeklyReportStatusCount> countByWeekStatusModelBetween(
            @Param("fromWeekStartDate") LocalDate fromWeekStartDate,
            @Param("toWeekStartDate") LocalDate toWeekStartDate);
}
