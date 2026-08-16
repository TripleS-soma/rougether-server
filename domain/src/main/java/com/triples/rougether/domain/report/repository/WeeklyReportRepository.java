package com.triples.rougether.domain.report.repository;

import com.triples.rougether.domain.report.entity.WeeklyReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    boolean existsByUserIdAndWeekStartDate(Long userId, LocalDate weekStartDate);

    List<WeeklyReport> findByUserIdOrderByWeekStartDateDesc(Long userId);

    Optional<WeeklyReport> findByIdAndUserId(Long id, Long userId);
}
