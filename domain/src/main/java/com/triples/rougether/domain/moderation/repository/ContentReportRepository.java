package com.triples.rougether.domain.moderation.repository;

import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    Optional<ContentReport> findByReporterUserIdAndTargetTypeAndTargetId(
            Long reporterUserId, ContentReportTargetType targetType, Long targetId);

    boolean existsByTargetTypeAndTargetId(ContentReportTargetType targetType, Long targetId);

    // 운영자 대기열: 상태별 최신순(idx_content_reports_status)
    Page<ContentReport> findByStatusOrderByIdDesc(ContentReportStatus status, Pageable page);

    Page<ContentReport> findAllByOrderByIdDesc(Pageable page);

    // 같은 대상의 대기 신고를 한 번에 닫음. 처리한 건수를 돌려줌.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ContentReport r set r.status = :status, r.resolvedAt = :now, r.resolvedBy = :adminId "
            + "where r.targetType = :targetType and r.targetId = :targetId "
            + "and r.status = com.triples.rougether.domain.moderation.entity.ContentReportStatus.RECEIVED")
    int resolveReceived(@Param("targetType") ContentReportTargetType targetType, @Param("targetId") Long targetId,
                        @Param("status") ContentReportStatus status, @Param("now") Instant now,
                        @Param("adminId") Long adminId);

    // 회원탈퇴: 탈퇴자가 한 신고만 지움. 탈퇴자 콘텐츠에 대한 신고는 작성자 스냅샷과 함께 남김.
    @Modifying
    @Query("delete from ContentReport r where r.reporterUserId = :userId")
    int deleteAllByReporterUserId(@Param("userId") Long userId);
}
