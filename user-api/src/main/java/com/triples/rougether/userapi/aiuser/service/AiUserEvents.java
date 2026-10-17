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
        List<Long> targets = jdbc.queryForList("SELECT DISTINCT u.id FROM users u JOIN ai_user_profiles p ON p.user_id=u.id "
                + "WHERE p.enabled=TRUE AND u.is_ai=TRUE AND u.deleted_at IS NULL AND (EXISTS "
                + "(SELECT 1 FROM feed_posts f WHERE f.id=? AND f.author_id=u.id AND f.deleted_at IS NULL) OR EXISTS "
                + "(SELECT 1 FROM feed_comments c WHERE c.post_id=? AND c.author_id=u.id AND c.deleted_at IS NULL))", Long.class, postId, postId);
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
