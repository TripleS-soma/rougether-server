package com.triples.rougether.domain.moderation.repository;

import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import java.time.Instant;
import java.util.List;
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

    // 자동 신고 중복 방지(#478): 같은 대상에 처리 대기 신고(사용자·자동)가 있으면 새로 만들지 않음.
    boolean existsByTargetTypeAndTargetIdAndStatus(ContentReportTargetType targetType, Long targetId,
                                                   ContentReportStatus status);

    // 운영자 대기열: 상태별 최신순(idx_content_reports_status)
    Page<ContentReport> findByStatusOrderByIdDesc(ContentReportStatus status, Pageable page);

    Page<ContentReport> findAllByOrderByIdDesc(Pageable page);

    // 운영자 대기열 위험도순(#479): 처리 대기 신고를 대상 판정의 유해 확률 내림차순(점수 없음은 뒤), 같으면 최신순.
    @Query(value = "select r from ContentReport r left join ContentScreening s "
            + "on s.targetType = r.targetType and s.targetId = r.targetId "
            + "where r.status = com.triples.rougether.domain.moderation.entity.ContentReportStatus.RECEIVED "
            + "order by case when s.harmProbability is null then 1 else 0 end, s.harmProbability desc, r.id desc",
            countQuery = "select count(r) from ContentReport r "
                    + "where r.status = com.triples.rougether.domain.moderation.entity.ContentReportStatus.RECEIVED")
    Page<ContentReport> findReceivedByRisk(Pageable page);

    // 판정 보충(#479): 처리 대기 신고가 있는 살아 있는 글 중 판정이 없거나 본문이 바뀐 것. 7일 창·작성자 종류와 무관.
    // 오래된 신고부터 처리함. 여러 신고가 같은 대상을 가리켜도 한 번만 나옴.
    @Query(nativeQuery = true, value = """
            SELECT 'FEED_POST' AS targetType, p.id AS targetId, p.author_id AS ownerUserId,
                   p.content AS content, NULL AS context
            FROM feed_posts p
            JOIN (SELECT target_id, MIN(id) AS first_report_id FROM content_reports
                  WHERE target_type = 'FEED_POST' AND status = 'RECEIVED' GROUP BY target_id) r
              ON r.target_id = p.id
            LEFT JOIN content_screenings s ON s.target_type = 'FEED_POST' AND s.target_id = p.id
            WHERE p.deleted_at IS NULL AND TRIM(p.content) <> ''
              AND (s.id IS NULL OR s.content_hash <> SHA2(p.content, 256))
            ORDER BY r.first_report_id
            LIMIT :limit
            """)
    List<ScreeningCandidate> findReportedPostCandidates(@Param("limit") int limit);

    @Query(nativeQuery = true, value = """
            SELECT 'FEED_COMMENT' AS targetType, c.id AS targetId, c.author_id AS ownerUserId,
                   c.content AS content, LEFT(p.content, 200) AS context
            FROM feed_comments c
            JOIN feed_posts p ON p.id = c.post_id
            JOIN (SELECT target_id, MIN(id) AS first_report_id FROM content_reports
                  WHERE target_type = 'FEED_COMMENT' AND status = 'RECEIVED' GROUP BY target_id) r
              ON r.target_id = c.id
            LEFT JOIN content_screenings s ON s.target_type = 'FEED_COMMENT' AND s.target_id = c.id
            WHERE c.deleted_at IS NULL AND TRIM(c.content) <> ''
              AND (s.id IS NULL OR s.content_hash <> SHA2(c.content, 256))
            ORDER BY r.first_report_id
            LIMIT :limit
            """)
    List<ScreeningCandidate> findReportedCommentCandidates(@Param("limit") int limit);

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
