package com.triples.rougether.domain.moderation.repository;

import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.entity.ContentScreening;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 자동 유해 판정(#478). 후보는 창(since) 이후 작성된 살아 있는 글·댓글 중 판정이 없거나 본문 해시가 바뀐 것.
// 탈퇴 작성자·AI 계정·동거 봇·빈 본문은 제외. 해시는 MySQL SHA2(utf8mb4 바이트)로 비교해 Java SHA-256(UTF-8)과 같음.
public interface ContentScreeningRepository extends JpaRepository<ContentScreening, Long> {

    Optional<ContentScreening> findByTargetTypeAndTargetId(ContentReportTargetType targetType, Long targetId);

    // 운영자 대기열 한 페이지의 대상 판정 점수(#479)
    List<ContentScreening> findByTargetTypeAndTargetIdIn(ContentReportTargetType targetType, List<Long> targetIds);

    // 재판정 시 기존 행을 잠가, 동시 실행이 둘 다 "대기 신고 없음"을 보고 자동 신고를 중복 생성하지 않게 함.
    // 첫 판정의 동시 INSERT 는 uk_content_screenings_target 이 한쪽 트랜잭션(신고 포함)을 롤백시킴.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContentScreening s where s.targetType = :targetType and s.targetId = :targetId")
    Optional<ContentScreening> findForUpdate(@Param("targetType") ContentReportTargetType targetType,
                                             @Param("targetId") Long targetId);

    @Query(nativeQuery = true, value = """
            SELECT 'FEED_POST' AS targetType, p.id AS targetId, p.author_id AS ownerUserId,
                   p.content AS content, NULL AS context
            FROM feed_posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN content_screenings s ON s.target_type = 'FEED_POST' AND s.target_id = p.id
            WHERE p.created_at >= :since AND p.deleted_at IS NULL
              AND u.deleted_at IS NULL AND u.is_ai = FALSE AND u.is_bot = FALSE
              AND TRIM(p.content) <> ''
              AND (s.id IS NULL OR s.content_hash <> SHA2(p.content, 256))
            ORDER BY p.id
            LIMIT :limit
            """)
    List<ScreeningCandidate> findPostCandidates(@Param("since") Instant since, @Param("limit") int limit);

    // 댓글은 수정 기능이 없어 판정이 한 번이면 충분함. 원 게시글이 삭제된 댓글은 노출되지 않으므로 제외.
    @Query(nativeQuery = true, value = """
            SELECT 'FEED_COMMENT' AS targetType, c.id AS targetId, c.author_id AS ownerUserId,
                   c.content AS content, LEFT(p.content, 200) AS context
            FROM feed_comments c
            JOIN feed_posts p ON p.id = c.post_id
            JOIN users u ON u.id = c.author_id
            LEFT JOIN content_screenings s ON s.target_type = 'FEED_COMMENT' AND s.target_id = c.id
            WHERE c.created_at >= :since AND c.deleted_at IS NULL AND p.deleted_at IS NULL
              AND u.deleted_at IS NULL AND u.is_ai = FALSE AND u.is_bot = FALSE
              AND TRIM(c.content) <> ''
              AND (s.id IS NULL OR s.content_hash <> SHA2(c.content, 256))
            ORDER BY c.id
            LIMIT :limit
            """)
    List<ScreeningCandidate> findCommentCandidates(@Param("since") Instant since, @Param("limit") int limit);

    // 이번 실행 사이에 창 밖으로 밀려나 판정 기회를 잃은 글·댓글 수(from 이상 to 미만 작성).
    @Query(nativeQuery = true, value = """
            SELECT
              (SELECT COUNT(*) FROM feed_posts p JOIN users u ON u.id = p.author_id
                 LEFT JOIN content_screenings s ON s.target_type = 'FEED_POST' AND s.target_id = p.id
                 WHERE p.created_at >= :from AND p.created_at < :to AND p.deleted_at IS NULL
                   AND u.deleted_at IS NULL AND u.is_ai = FALSE AND u.is_bot = FALSE
                   AND TRIM(p.content) <> '' AND s.id IS NULL)
            + (SELECT COUNT(*) FROM feed_comments c JOIN feed_posts p ON p.id = c.post_id
                 JOIN users u ON u.id = c.author_id
                 LEFT JOIN content_screenings s ON s.target_type = 'FEED_COMMENT' AND s.target_id = c.id
                 WHERE c.created_at >= :from AND c.created_at < :to AND c.deleted_at IS NULL
                   AND p.deleted_at IS NULL AND u.deleted_at IS NULL AND u.is_ai = FALSE AND u.is_bot = FALSE
                   AND TRIM(c.content) <> '' AND s.id IS NULL)
            """)
    long countUnscreenedCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
