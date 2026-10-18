package com.triples.rougether.userapi.aiuser.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.feed.service.FeedQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class AiUserSocialActivity {
    private final JdbcTemplate jdbc;
    private final FeedQueryService feed;
    private final Clock clock;

    public record Context(Map<String,Object> state,List<AiUserObserver.SourceReference> references) {}
    private static final Set<AiUserActionExecutor.Action> ACTIONS=Set.of(
            AiUserActionExecutor.Action.FEED_POST,AiUserActionExecutor.Action.FEED_COMMENT,AiUserActionExecutor.Action.FEED_LIKE);

    public Context observe(long user) {
        var now=clock.instant();
        var references=new LinkedHashSet<AiUserObserver.SourceReference>();
        var state=new LinkedHashMap<String,Object>();
        var candidates=new ArrayList<Map<String,Object>>();
        List<Long> posts=jdbc.queryForList("SELECT f.id FROM feed_posts f JOIN users a ON a.id=f.author_id "
                + "WHERE f.deleted_at IS NULL AND a.deleted_at IS NULL AND a.is_bot=FALSE AND a.id<>? AND f.created_at>=? "
                + "AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE (b.blocker_user_id=? AND b.blocked_user_id=a.id) "
                + "OR (b.blocker_user_id=a.id AND b.blocked_user_id=?)) ORDER BY a.is_ai ASC,f.id DESC LIMIT 12",
                Long.class,user,AiUserJobs.timestamp(now.minus(Duration.ofDays(7))),user,user);
        for (Long id:posts) {
            if (count("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND proactive_post_id=?",user,id)>0
                    || count("SELECT COUNT(*) FROM ai_user_social_actions WHERE post_id=? AND action_name='FEED_COMMENT' AND proactive=TRUE",id)>=2)
                continue;
            var post=feed.get(user,id);
            var comments=jdbc.queryForList("SELECT c.id,c.author_id,c.content,c.parent_comment_id,c.reply_to_comment_id FROM feed_comments c JOIN users a ON a.id=c.author_id "
                    + "WHERE c.post_id=? AND c.deleted_at IS NULL AND a.deleted_at IS NULL AND NOT EXISTS "
                    + "(SELECT 1 FROM user_blocks b WHERE (b.blocker_user_id=? AND b.blocked_user_id=a.id) "
                    + "OR (b.blocker_user_id=a.id AND b.blocked_user_id=?)) AND (c.parent_comment_id IS NULL OR NOT EXISTS "
                    + "(SELECT 1 FROM feed_comments root JOIN user_blocks rb ON "
                    + "(rb.blocker_user_id=? AND rb.blocked_user_id=root.author_id) OR "
                    + "(rb.blocker_user_id=root.author_id AND rb.blocked_user_id=?) WHERE root.id=c.parent_comment_id)) "
                    + "ORDER BY c.id DESC LIMIT 3",id,user,user,user,user);
            var preview=new LinkedHashMap<String,Object>();
            preview.put("postId",id);
            preview.put("author",Map.of("userId",post.author().userId(),"nickname",Objects.requireNonNullElse(post.author().nickname(),"")));
            preview.put("content",clip(post.content(),700));
            preview.put("createdAt",post.createdAt());
            preview.put("likedByMe",post.likedByMe());
            preview.put("recentComments",comments.stream().map(c -> {
                var item=new LinkedHashMap<String,Object>();
                item.put("commentId",c.get("id")); item.put("authorUserId",c.get("author_id"));
                item.put("content",clip((String)c.get("content"),180));
                item.put("parentCommentId",c.get("parent_comment_id")); item.put("replyToCommentId",c.get("reply_to_comment_id"));
                return item;
            }).toList());
            references.add(new AiUserObserver.SourceReference("FEED_POST",id));
            for (var comment:comments) references.add(new AiUserObserver.SourceReference("FEED_COMMENT",((Number)comment.get("id")).longValue()));
            candidates.add(preview);
            if (candidates.size()==3) break;
        }
        state.put("candidates",candidates);
        var recent=new ArrayList<Map<String,Object>>();
        for (var row:jdbc.queryForList("SELECT action_name,post_id,topic,created_at FROM ai_user_social_actions "
                + "WHERE user_id=? AND created_at>=? ORDER BY created_at DESC LIMIT 8",user,AiUserJobs.timestamp(now.minus(Duration.ofDays(7))))) {
            long post=((Number)row.get("post_id")).longValue();
            try { feed.get(user,post); }
            catch (BusinessException inaccessible) { continue; }
            recent.add(row);
            references.add(new AiUserObserver.SourceReference("FEED_POST",post));
        }
        state.put("recentActions",recent);
        state.put("postLimitPerDay",1);
        state.put("proactiveCommentLimitPerDay",3);
        var postBlock=dailyRejection(user,"FEED_POST");
        var commentBlock=dailyRejection(user,"FEED_COMMENT");
        var likeBlock=dailyRejection(user,"FEED_LIKE");
        state.put("postAvailable",postBlock==null);
        state.put("commentAvailable",commentBlock==null);
        state.put("likeAvailable",likeBlock==null);
        state.put("firstPost",count("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND action_name='FEED_POST'",user)==0);
        var actions=new ArrayList<String>();
        if (Boolean.TRUE.equals(state.get("postAvailable"))) actions.add("FEED_POST");
        if (!candidates.isEmpty() && Boolean.TRUE.equals(state.get("commentAvailable"))) actions.add("FEED_COMMENT");
        if (candidates.stream().anyMatch(p -> Boolean.FALSE.equals(p.get("likedByMe")))
                && Boolean.TRUE.equals(state.get("likeAvailable"))) actions.add("FEED_LIKE");
        actions.add("WAIT");
        state.put("allowedActions",actions);
        String unavailable="DAILY_LIMIT";
        if (postBlock==null || commentBlock==null || likeBlock==null) unavailable="RECENT_PARTICIPATION";
        if (candidates.isEmpty()) unavailable="NO_CANDIDATE";
        if (postBlock==AiUserWaitReason.COOLDOWN || commentBlock==AiUserWaitReason.COOLDOWN || likeBlock==AiUserWaitReason.COOLDOWN)
            unavailable="COOLDOWN";
        // 가능한 행동이 남았을 때 대기 사유를 보내면 모델이 방문 전체의 제한으로 해석함.
        if (actions.size()==1) state.put("unavailableReason",unavailable);
        return new Context(state,List.copyOf(references));
    }

    // requireClaim의 singleton 잠금 안에서 검사함. 실행·이력·작업 완료는 같은 트랜잭션에 참여함.
    @Transactional(propagation=Propagation.MANDATORY)
    public AiUserWaitReason reject(AiUserJobs.Job job,AiUserActionExecutor.Action action,JsonNode payload) {
        if (!ACTIONS.contains(action)) return null;
        boolean proactive=proactive(job);
        if (!proactive) return null;
        var budget=dailyRejection(job.userId(),action.name());
        if (budget!=null) return budget;
        if (action!=AiUserActionExecutor.Action.FEED_POST && count("SELECT COUNT(*) FROM feed_posts WHERE id=? AND author_id=?",
                AiUserActionExecutor.id(payload,"postId"),job.userId())>0) return AiUserWaitReason.RECENT_PARTICIPATION;
        if (action==AiUserActionExecutor.Action.FEED_POST || action==AiUserActionExecutor.Action.FEED_COMMENT) {
            String hash=fingerprint(payload.path("content").asText(""));
            if (count("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND proactive_content_hash=?",job.userId(),hash)>0)
                return AiUserWaitReason.RECENT_PARTICIPATION;
        }
        if (action==AiUserActionExecutor.Action.FEED_COMMENT) {
            long post=AiUserActionExecutor.id(payload,"postId");
            if (count("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND proactive_post_id=?",job.userId(),post)>0
                    || count("SELECT COUNT(*) FROM ai_user_social_actions WHERE post_id=? AND proactive=TRUE AND action_name='FEED_COMMENT'",post)>=2)
                return AiUserWaitReason.RECENT_PARTICIPATION;
        }
        if (action==AiUserActionExecutor.Action.FEED_LIKE
                && (!payload.path("liked").asBoolean(true) || count("SELECT COUNT(*) FROM feed_likes WHERE user_id=? AND post_id=?",
                job.userId(),AiUserActionExecutor.id(payload,"postId"))>0)) return AiUserWaitReason.RECENT_PARTICIPATION;
        return null;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void record(AiUserJobs.Job job,AiUserActionExecutor.Action action,JsonNode payload,Object result) {
        if (action==AiUserActionExecutor.Action.WAIT) {
            jdbc.update("UPDATE ai_user_jobs SET wait_reason=? WHERE id=?",AiUserWaitReason.parse(payload.path("reason").asText(null)).name(),job.id());
            return;
        }
        if (!ACTIONS.contains(action)) return;
        boolean proactive=proactive(job);
        long post=action==AiUserActionExecutor.Action.FEED_POST?((Number)result).longValue():AiUserActionExecutor.id(payload,"postId");
        String content=payload.path("content").asText("");
        String topic=action==AiUserActionExecutor.Action.FEED_POST?clip(payload.path("topic").asText(content),120):null;
        jdbc.update("INSERT INTO ai_user_social_actions(job_id,user_id,action_name,post_id,proactive,proactive_post_id,proactive_content_hash,topic,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)",job.id(),job.userId(),action.name(),post,proactive,
                proactive && action==AiUserActionExecutor.Action.FEED_COMMENT?post:null,
                proactive && !content.isBlank()?fingerprint(content):null,topic,AiUserJobs.timestamp(clock.instant()));
    }

    private AiUserWaitReason dailyRejection(long user,String action) {
        var now=clock.instant();
        var start=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        int maximum=action.equals("FEED_POST")?1:action.equals("FEED_COMMENT")?3:5;
        if (count("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND action_name=? AND proactive=TRUE AND created_at>=?",
                user,action,AiUserJobs.timestamp(start))>=maximum) return AiUserWaitReason.DAILY_LIMIT;
        long minutes=action.equals("FEED_POST")?480:action.equals("FEED_COMMENT")?30:10;
        if (count("SELECT COUNT(*) FROM ai_user_social_actions WHERE user_id=? AND action_name=? AND proactive=TRUE AND created_at>?",
                user,action,AiUserJobs.timestamp(now.minus(Duration.ofMinutes(minutes))))>0) return AiUserWaitReason.COOLDOWN;
        return null;
    }
    private int count(String sql,Object... args) { return jdbc.queryForObject(sql,Integer.class,args); }
    private static boolean proactive(AiUserJobs.Job job) { return !job.sourceType().equals("FEED_COMMENT"); }
    private static String fingerprint(String value) {
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+","");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String clip(String value,int maximum) {
        if (value==null) return "";
        return value.substring(0,value.offsetByCodePoints(0,Math.min(maximum,value.codePointCount(0,value.length()))));
    }
}
