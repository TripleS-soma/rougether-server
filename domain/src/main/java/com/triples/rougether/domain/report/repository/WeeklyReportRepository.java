package com.triples.rougether.domain.report.repository;

import com.triples.rougether.domain.report.entity.WeeklyReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    boolean existsByUserIdAndWeekStartDate(Long userId, LocalDate weekStartDate);

    List<WeeklyReport> findByUserIdOrderByWeekStartDateDesc(Long userId);

    Optional<WeeklyReport> findByIdAndUserId(Long id, Long userId);

    // 관리자 AI 회고 관측: 주차 범위의 회고를 주차·상태·모델별 건수로 집계. 탈퇴 회원 회고는 하드 파기되므로 별도 제외 없음.
    @Query("select w.weekStartDate as weekStartDate, w.status as status, w.model as model, "
            + "count(w) as reportCount, max(w.generatedAt) as lastGeneratedAt "
            + "from WeeklyReport w "
            + "where w.weekStartDate between :fromWeekStartDate and :toWeekStartDate "
            + "group by w.weekStartDate, w.status, w.model "
            + "order by w.weekStartDate desc")
    List<WeeklyReportStatusCount> countByWeekStatusModelBetween(
            @Param("fromWeekStartDate") LocalDate fromWeekStartDate,
            @Param("toWeekStartDate") LocalDate toWeekStartDate);
}
