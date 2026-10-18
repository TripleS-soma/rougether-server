package com.triples.rougether.userapi.aiuser.service;

import com.triples.rougether.userapi.aiuser.config.AiUserProperties;

import com.triples.rougether.domain.member.entity.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiUserEvents {
    private final JdbcTemplate jdbc;
    private final AiUserJobs jobs;
    private final AiUserProperties properties;

    // 글/댓글/메시지 트랜잭션 안에서만 호출하여 사람 사건과 예약 작업이 함께 커밋됨.
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(User actor, long postId) {
        if (!properties.enabled() || !actor.isHuman()) return;
        List<Long> candidates = jdbc.queryForList("SELECT p.user_id FROM ai_user_profiles p JOIN users u ON u.id=p.user_id "
                + "WHERE p.enabled=TRUE AND u.deleted_at IS NULL AND u.is_ai=TRUE ORDER BY p.user_id", Long.class);
        if (!candidates.isEmpty()) {
            long target = candidates.get((int) (postId % candidates.size()));
            jobs.enqueue(target, "post:" + postId + ":" + target, 100, "FEED_POST", postId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void comment(User actor, long postId, long commentId) {
        if (!properties.enabled() || !actor.isHuman()) return;
        var source = jdbc.queryForMap("SELECT reply_to_comment_id FROM feed_comments WHERE id=? AND post_id=?", commentId, postId);
        Object replyTo = source.get("reply_to_comment_id");
        List<Long> targets;
        if (replyTo != null) {
            // 직접 받은 답글은 해당 계정만 처리함. 사람에게 쓴 답글을 다른 AI가 가로채지 않음.
            targets = jdbc.queryForList("SELECT u.id FROM feed_comments c JOIN users u ON u.id=c.author_id "
                    + "JOIN ai_user_profiles p ON p.user_id=u.id WHERE c.id=? AND c.post_id=? AND c.deleted_at IS NULL "
                    + "AND p.enabled=TRUE AND u.is_ai=TRUE AND u.deleted_at IS NULL AND NOT EXISTS "
                    + "(SELECT 1 FROM user_blocks b WHERE (b.blocker_user_id=? AND b.blocked_user_id=u.id) "
                    + "OR (b.blocker_user_id=u.id AND b.blocked_user_id=?))", Long.class, replyTo, postId, actor.getId(), actor.getId());
        } else {
            targets = jdbc.queryForList("SELECT DISTINCT u.id FROM users u JOIN ai_user_profiles p ON p.user_id=u.id "
                    + "WHERE p.enabled=TRUE AND u.is_ai=TRUE AND u.deleted_at IS NULL AND (EXISTS "
                    + "(SELECT 1 FROM feed_posts f WHERE f.id=? AND f.author_id=u.id AND f.deleted_at IS NULL) OR EXISTS "
                    + "(SELECT 1 FROM feed_comments c WHERE c.post_id=? AND c.author_id=u.id AND c.deleted_at IS NULL)) "
                    + "AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE (b.blocker_user_id=? AND b.blocked_user_id=u.id) "
                    + "OR (b.blocker_user_id=u.id AND b.blocked_user_id=?)) ORDER BY u.id", Long.class,
                    postId, postId, actor.getId(), actor.getId());
        }
        // 여러 AI가 같은 글에 답해도 하나의 사람 댓글에 한 계정만 반응함.
        if (!targets.isEmpty()) {
            long target = targets.get((int) (commentId % targets.size()));
            jobs.enqueue(target, "comment:" + commentId + ":" + target, 100, "FEED_COMMENT", commentId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void chat(User actor, long roomId, long messageId) {
        if (!properties.enabled() || !actor.isHuman()) return;
        List<Long> targets = jdbc.queryForList("SELECT hm.user_id FROM house_members hm JOIN chat_rooms cr ON cr.house_id=hm.house_id "
                + "JOIN ai_user_profiles p ON p.user_id=hm.user_id JOIN users u ON u.id=hm.user_id "
                + "WHERE cr.id=? AND hm.status='ACTIVE' AND p.enabled=TRUE AND u.deleted_at IS NULL", Long.class, roomId);
        if (!targets.isEmpty()) {
            long target = targets.get((int) (messageId % targets.size()));
            jobs.enqueue(target, "chat:" + messageId + ":" + target, 100, "CHAT", messageId);
        }
    }
}
