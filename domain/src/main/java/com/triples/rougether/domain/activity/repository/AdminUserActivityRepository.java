package com.triples.rougether.domain.activity.repository;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// 기존 업무 기록을 하루 범위로 집계한다. 요청 경로에 새 쓰기를 추가하지 않고 본문·이메일도 읽지 않는다.
public interface AdminUserActivityRepository extends Repository<User, Long> {

    @Transactional(readOnly = true, timeout = 10)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query(value = """
            SELECT u.id AS userId, u.nickname AS nickname, e.feature AS feature,
                   COUNT(*) AS recordCount, MIN(e.occurred_at) AS firstRecordedAt,
                   MAX(e.occurred_at) AS lastRecordedAt
            FROM (
                SELECT user_id, 'ACCESS' AS feature, NULL AS occurred_at
                FROM user_daily_activity WHERE activity_date = :activityDate
                UNION ALL
                SELECT id, 'ACCESS', last_accessed_at FROM users
                WHERE last_accessed_at >= :fromInclusive AND last_accessed_at < :toExclusive
                UNION ALL
                SELECT id, 'SIGNUP', created_at FROM users
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT user_id, 'ROUTINE_CREATE', created_at FROM routines
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT r.user_id, 'ROUTINE_COMPLETE', l.completed_at FROM routine_logs l
                JOIN routines r ON r.id = l.routine_id
                WHERE l.status = 'COMPLETED' AND l.completed_at >= :fromInclusive AND l.completed_at < :toExclusive
                UNION ALL
                SELECT user_id, 'TODO_CREATE', created_at FROM todos
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT user_id, 'TODO_COMPLETE', completed_at FROM todos
                WHERE status = 'COMPLETED' AND completed_at >= :fromInclusive AND completed_at < :toExclusive
                UNION ALL
                SELECT room_user_id, 'ROOM_DECORATE', updated_at FROM room_item_placements
                WHERE updated_at >= :fromInclusive AND updated_at < :toExclusive
                UNION ALL
                SELECT room_user_id, 'ROOM_DECORATE', saved_at FROM room_surface_slots
                WHERE saved_at >= :fromInclusive AND saved_at < :toExclusive
                UNION ALL
                SELECT user_id, 'AI_FURNITURE', created_at FROM furniture_generation_jobs
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT user_id, CASE WHEN reason = 'GACHA_DRAW' THEN 'GACHA' ELSE 'SHOP_PURCHASE' END, created_at
                FROM wallet_histories WHERE reason IN ('GACHA_DRAW', 'SHOP_PURCHASE')
                AND created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT buyer_user_id, 'MARKET_BUY', created_at FROM market_purchases
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT seller_user_id, 'MARKET_SELL', created_at FROM market_purchases
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT buyer_user_id, 'MARKET_BUY', created_at FROM market_trades
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT seller_user_id, 'MARKET_SELL', created_at FROM market_trades
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT author_id, 'FEED_POST', created_at FROM feed_posts
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT author_id, 'FEED_COMMENT', created_at FROM feed_comments
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT user_id, 'HOUSE_JOIN', joined_at FROM house_members
                WHERE joined_at >= :fromInclusive AND joined_at < :toExclusive
                UNION ALL
                SELECT sender_user_id, 'HOUSE_CHEER', created_at FROM house_member_cheers
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT sender_user_id, 'CHAT', created_at FROM chat_messages
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT author_id, 'GUESTBOOK', created_at FROM room_guestbooks
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
                UNION ALL
                SELECT user_id, 'MINIGAME', started_at FROM minigame_runs
                WHERE started_at >= :fromInclusive AND started_at < :toExclusive
                UNION ALL
                SELECT user_id, 'ATTENDANCE', created_at FROM attendance_check_ins
                WHERE created_at >= :fromInclusive AND created_at < :toExclusive
            ) e JOIN users u ON u.id = e.user_id
            WHERE u.deleted_at IS NULL AND u.is_bot = FALSE AND u.is_ai = FALSE
            GROUP BY u.id, u.nickname, e.feature
            """, nativeQuery = true)
    List<FeatureRecord> findDailyRecords(@Param("activityDate") LocalDate activityDate,
                                         @Param("fromInclusive") Instant fromInclusive,
                                         @Param("toExclusive") Instant toExclusive);

    interface FeatureRecord {
        Long getUserId();
        String getNickname();
        String getFeature();
        long getRecordCount();
        Instant getFirstRecordedAt();
        Instant getLastRecordedAt();
    }
}
