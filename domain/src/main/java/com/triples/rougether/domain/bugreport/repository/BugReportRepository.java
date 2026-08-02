package com.triples.rougether.domain.bugreport.repository;

import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BugReportRepository extends JpaRepository<BugReport, Long> {

    List<BugReport> findByUserIdOrderByIdDesc(Long userId);

    // 어드민 목록: 제보자 닉네임 표시를 위해 user fetch join.
    @Query("select r from BugReport r join fetch r.user order by r.id desc")
    List<BugReport> findAllWithUserOrderByIdDesc();

    @Query("select r from BugReport r join fetch r.user where r.status = :status order by r.id desc")
    List<BugReport> findByStatusWithUserOrderByIdDesc(@Param("status") BugReportStatus status);
}
