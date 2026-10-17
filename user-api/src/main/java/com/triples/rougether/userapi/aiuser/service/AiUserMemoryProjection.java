package com.triples.rougether.userapi.aiuser.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(isolation=Isolation.READ_COMMITTED)
public class AiUserMemoryProjection {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiUserJobs jobs;
    private final AiUserObserver observer;
    private final Clock clock;

    public record Source(String type,Long id,String fingerprint) {}
    public record Fact(String text,List<Source> sources,List<String> episodes) {}
    public record MemoryRecord(String episode,List<Source> sources) {}

    public List<String> invalidEpisodes(long user,List<MemoryRecord> records) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_profiles p JOIN users u ON u.id=p.user_id "
                + "WHERE p.user_id=? AND p.enabled=TRUE AND u.deleted_at IS NULL AND u.is_ai=TRUE",Integer.class,user)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        return records.stream().filter(record -> record.sources().isEmpty() || record.sources().stream().anyMatch(s -> !valid(user,s)))
                .map(MemoryRecord::episode).toList();
    }

    public void recordAction(AiUserJobs.Job job,String action,JsonNode payload) {
        jdbc.update("UPDATE ai_user_jobs SET action_name=?,action_payload=? WHERE id=?",action,mapper.writeValueAsString(payload),job.id());
    }

    // 원문/권한이 바뀐 기억은 모델에 전달하지 않으며, 채택한 출처는 실행 직전에도 재확인함.
    public List<Fact> authorize(long id,String owner,long token,List<Fact> candidates) {
        var job=jobs.requireClaim(id,owner,token);
        var accepted=new ArrayList<Fact>();
        var references=new LinkedHashSet<Source>();
        for (var fact:candidates) {
            if (fact.sources().isEmpty() || fact.sources().stream().anyMatch(s -> !valid(job.userId(),s))) continue;
            var next=new LinkedHashSet<>(references);
            next.addAll(fact.sources());
            if (next.size()>64) continue;
            references=next;
            accepted.add(fact);
        }
        jdbc.update("UPDATE ai_user_jobs SET memory_sources=? WHERE id=?",mapper.writeValueAsString(references),id);
        return accepted;
    }

    public void requireMemorySources(AiUserJobs.Job job) {
        var raw=jdbc.queryForObject("SELECT memory_sources FROM ai_user_jobs WHERE id=?",String.class,job.id());
        for (var source:decode(raw,Source[].class))
            if (!valid(job.userId(),source)) throw new ResponseStatusException(HttpStatus.CONFLICT,"AI memory source changed");
    }

    public List<Map<String,Object>> pending(int limit) {
        var rows=jdbc.queryForList("SELECT j.* FROM ai_user_jobs j JOIN ai_user_profiles p ON p.user_id=j.user_id "
                + "JOIN users u ON u.id=j.user_id WHERE j.status='DONE' AND j.memory_status='PENDING' "
                + "AND j.action_name IS NOT NULL AND p.enabled=TRUE AND u.deleted_at IS NULL AND u.is_ai=TRUE "
                + "AND (j.memory_ready_at IS NULL OR j.memory_ready_at<=?) "
                + "AND EXISTS (SELECT 1 FROM ai_user_runtime WHERE id=1 AND enabled=TRUE) ORDER BY j.id LIMIT ?",
                AiUserJobs.timestamp(clock.instant()),limit);
        var exports=new ArrayList<Map<String,Object>>();
        for (var row:rows) {
            long id=((Number)row.get("id")).longValue(),user=((Number)row.get("user_id")).longValue();
            try {
                String type=(String)row.get("source_type");
                Long sourceId=row.get("source_id")==null?null:((Number)row.get("source_id")).longValue();
                if (!observer.accessible(user,type,sourceId)) throw new IllegalArgumentException();
                var sources=new LinkedHashSet<Source>();
                boolean includeAction=true;
                for (var ref:decode(row.get("context_sources"),AiUserObserver.SourceReference[].class)) {
                    if (!observer.accessible(user,ref.type(),ref.id())) { includeAction=false; break; }
                    if (!ref.type().equals("SCHEDULE")) sources.add(new Source(ref.type(),ref.id(),fingerprint(ref.type(),ref.id())));
                }
                for (var ref:decode(row.get("memory_sources"),Source[].class)) {
                    if (!valid(user,ref)) { includeAction=false; break; }
                    sources.add(ref);
                }
                if (sourceId!=null) sources.add(new Source(type,sourceId,fingerprint(type,sourceId)));
                sources.add(new Source("AI_JOB",id,fingerprint("AI_JOB",id)));
                if (sources.size()>64) includeAction=false;
                if (!includeAction) {
                    sources.clear();
                    if (sourceId==null) throw new IllegalArgumentException();
                    sources.add(new Source(type,sourceId,fingerprint(type,sourceId)));
                }
                // 닉네임 대신 canonical 작성자 ID와 이 작업의 실제 원문만 반영함.
                Object observation=switch(type) {
                    case "FEED_POST" -> jdbc.queryForMap("SELECT author_id,content FROM feed_posts WHERE id=?",sourceId);
                    case "FEED_COMMENT" -> jdbc.queryForMap("SELECT author_id,post_id,content FROM feed_comments WHERE id=?",sourceId);
                    case "CHAT" -> jdbc.queryForMap("SELECT sender_user_id,room_id,content FROM chat_messages WHERE id=?",sourceId);
                    default -> Map.of("type",type);
                };
                String body=mapper.writeValueAsString(Map.of("aiUserId",user,"sourceType",type,
                        "primarySourceId",sourceId==null?0:sourceId,"includeAction",includeAction,
                        "observation",observation,"action",row.get("action_name"),
                        "payload",tree(row.get("action_payload")),"receipt",tree(row.get("result_text"))));
                if (body.getBytes(StandardCharsets.UTF_8).length>32000) throw new IllegalArgumentException();
                exports.add(Map.of("aiUserId",user,"jobId",id,"body",body,"sources",sources,
                        "occurredAt",((java.sql.Timestamp)row.get("finished_at")).toInstant().toString()));
            } catch (org.springframework.dao.DataAccessException transientFailure) { throw transientFailure; }
            catch (RuntimeException unavailable) {
                jdbc.update("UPDATE ai_user_jobs SET memory_status='REVOKED' WHERE id=?",id);
            }
        }
        return exports;
    }

    public void acknowledge(long id,boolean success,boolean revoked) {
        if (success || revoked) jdbc.update("UPDATE ai_user_jobs SET memory_status=? WHERE id=? AND status='DONE' AND memory_status='PENDING'",revoked?"REVOKED":"DONE",id);
        else jdbc.update("UPDATE ai_user_jobs SET memory_status=CASE WHEN memory_attempts>=2 THEN 'FAILED' ELSE 'PENDING' END, "
                + "memory_attempts=memory_attempts+1,memory_ready_at=? "
                + "WHERE id=? AND status='DONE' AND memory_status='PENDING'",AiUserJobs.timestamp(clock.instant().plus(Duration.ofMinutes(30))),id);
    }

    private boolean valid(long user,Source source) {
        try { return observer.accessible(user,source.type(),source.id())
                && MessageDigest.isEqual(fingerprint(source.type(),source.id()).getBytes(StandardCharsets.UTF_8),
                        source.fingerprint().getBytes(StandardCharsets.UTF_8)); }
        catch (org.springframework.dao.EmptyResultDataAccessException missing) { return false; }
        catch (org.springframework.dao.DataAccessException transientFailure) { throw transientFailure; }
        catch (RuntimeException missing) { return false; }
    }

    private String fingerprint(String type,Long id) {
        String query=switch(type) {
            case "FEED_POST" -> "SELECT author_id,content FROM feed_posts WHERE id=? AND deleted_at IS NULL";
            case "FEED_COMMENT" -> "SELECT author_id,post_id,content FROM feed_comments WHERE id=? AND deleted_at IS NULL";
            case "CHAT" -> "SELECT sender_user_id,room_id,content FROM chat_messages WHERE id=?";
            case "MEMBERSHIP" -> "SELECT user_id,house_id,status FROM house_members WHERE id=?";
            case "AI_JOB" -> "SELECT user_id,result_text,action_name,action_payload FROM ai_user_jobs WHERE id=? AND status='DONE'";
            default -> throw new IllegalArgumentException("Unsupported memory source");
        };
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(jdbc.queryForMap(query,id)))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private JsonNode tree(Object value) { return mapper.readTree(text(value)); }
    private static String text(Object value) { return value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):value.toString(); }
    private <T> List<T> decode(Object raw,Class<T[]> type) {
        return raw==null?List.of():Arrays.asList(mapper.readValue(text(raw),type));
    }
}
