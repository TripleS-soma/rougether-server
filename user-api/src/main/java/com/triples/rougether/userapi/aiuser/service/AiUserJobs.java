package com.triples.rougether.userapi.aiuser.service;

import com.triples.rougether.userapi.aiuser.config.AiUserProperties;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiUserJobs {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AiUserProperties properties;

    public record Job(long id, long userId, String sourceType, Long sourceId,
            String owner, long fencingToken, Instant leaseUntil) {}

    @Transactional
    public void enqueue(long userId, String key, int priority, String sourceType, Long sourceId) {
        if (!properties.enabled()) return;
        try {
            jdbc.update("INSERT INTO ai_user_jobs(user_id,job_key,priority,source_type,source_id,ready_at,created_at) "
                    + "SELECT ?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM ai_user_profiles p JOIN users u ON u.id=p.user_id "
                    + "WHERE p.user_id=? AND p.enabled=TRUE AND u.is_ai=TRUE AND u.deleted_at IS NULL)",
                    userId, key, priority, sourceType, sourceId, timestamp(clock.instant()), timestamp(clock.instant()), userId);
        } catch (DuplicateKeyException duplicate) {
            // 동일 사건/일정의 재전송은 기존 예약을 유지함.
        }
    }

    // 모든 프로세스가 같은 singleton을 잠그므로 다른 계정도 동시에 실행되지 않음.
    @Transactional
    public Job claim(String owner) {
        requireOwner(owner);
        Map<String, Object> runtime = lockRuntime();
        if (!properties.enabled() || !Boolean.TRUE.equals(runtime.get("enabled"))) return null;
        Instant now = clock.instant();
        Timestamp until = (Timestamp) runtime.get("lease_until");
        if (runtime.get("current_job_id") != null && until != null && until.toInstant().isAfter(now)) return null;
        if (runtime.get("current_job_id") != null) {
            jdbc.update("UPDATE ai_user_jobs SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END, "
                    + "finished_at=CASE WHEN attempts>=3 THEN ? ELSE NULL END, "
                    + "ready_at=? WHERE id=? AND status='RUNNING'", timestamp(now), timestamp(now), runtime.get("current_job_id"));
        }
        var start = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        // 우선순위: 사람 사건(100) > 예약(10). 같은 우선순위에서는 오래된 작업부터 실행함.
        List<Map<String, Object>> due = jdbc.queryForList("SELECT j.* FROM ai_user_jobs j "
                + "JOIN ai_user_profiles p ON p.user_id=j.user_id JOIN users u ON u.id=j.user_id "
                + "WHERE j.status='PENDING' AND j.ready_at<=? AND p.enabled=TRUE AND u.is_ai=TRUE AND u.deleted_at IS NULL "
                + "AND (SELECT COUNT(*) FROM ai_user_jobs done WHERE done.user_id=j.user_id "
                + "AND done.finished_at>=? AND done.status='DONE' AND done.action_name<>'WAIT')<CASE WHEN j.priority>=100 THEN ? ELSE ? END "
                + "ORDER BY j.priority DESC,j.created_at,j.id LIMIT 1",
                timestamp(now), timestamp(start), properties.dailyJobLimit(), Math.max(1,properties.dailyJobLimit()*2/3));
        if (due.isEmpty()) {
            clearRuntime();
            return null;
        }
        var row = due.getFirst();
        long id = ((Number) row.get("id")).longValue();
        long token = ((Number) runtime.get("fencing_token")).longValue() + 1;
        // MySQL TIMESTAMP(6)과 반환값의 정밀도를 맞춰 재조회 시 같은 lease를 보장함.
        Instant deadline = timestamp(now.plus(properties.leaseDuration())).toInstant();
        jdbc.update("UPDATE ai_user_jobs SET status='RUNNING',attempts=attempts+1,lease_owner=?,fencing_token=? WHERE id=?", owner, token, id);
        jdbc.update("UPDATE ai_user_runtime SET current_job_id=?,lease_owner=?,fencing_token=?,lease_until=? WHERE id=1",
                id, owner, token, timestamp(deadline));
        return new Job(id, ((Number) row.get("user_id")).longValue(), (String) row.get("source_type"),
                row.get("source_id") == null ? null : ((Number) row.get("source_id")).longValue(), owner, token, deadline);
    }

    @Transactional
    public Job requireClaim(long jobId, String owner, long token) {
        requireOwner(owner);
        var runtime = lockRuntime();
        if (!properties.enabled() || !Boolean.TRUE.equals(runtime.get("enabled"))) conflict("AI runtime is paused");
        if (runtime.get("current_job_id") == null || ((Number) runtime.get("current_job_id")).longValue() != jobId
                || !owner.equals(runtime.get("lease_owner")) || ((Number) runtime.get("fencing_token")).longValue() != token
                || runtime.get("lease_until") == null || !((Timestamp) runtime.get("lease_until")).toInstant().isAfter(clock.instant()))
            conflict("Expired or superseded AI lease");
        var row = jdbc.queryForMap("SELECT j.* FROM ai_user_jobs j JOIN ai_user_profiles p ON p.user_id=j.user_id "
                + "JOIN users u ON u.id=j.user_id WHERE j.id=? AND j.status='RUNNING' "
                + "AND p.enabled=TRUE AND u.deleted_at IS NULL AND u.is_ai=TRUE", jobId);
        return new Job(jobId, ((Number) row.get("user_id")).longValue(), (String) row.get("source_type"),
                row.get("source_id") == null ? null : ((Number) row.get("source_id")).longValue(), owner, token,
                ((Timestamp) runtime.get("lease_until")).toInstant());
    }

    // 호출자의 action 트랜잭션에 참여함. 업무 쓰기·기억·receipt가 함께 커밋되거나 롤백됨.
    @Transactional
    public void finish(Job job, String result, String reflection) {
        requireClaim(job.id(), job.owner(), job.fencingToken());
        Instant now = clock.instant();
        jdbc.update("UPDATE ai_user_jobs SET status='DONE',result_text=?,finished_at=? WHERE id=?", result, timestamp(now), job.id());
        if (reflection != null && !reflection.isBlank()) {
            if (reflection.length() > 1000) throw new IllegalArgumentException("Memory too long");
            jdbc.update("INSERT INTO ai_user_memories(user_id,job_id,kind,content,source_type,source_id,created_at,expires_at) "
                    + "VALUES (?,?,'REFLECTION',?,?,?,?,?)", job.userId(), job.id(), reflection, job.sourceType(), job.sourceId(),
                    timestamp(now), timestamp(now.plus(Duration.ofDays(30))));
        }
        clearRuntime();
    }

    @Transactional(readOnly = true)
    public String completedResult(long id, String owner, long token) {
        // receipt도 원래 claim 소유자와 펜싱 번호에 묶음. 다른 실행의 결과를 읽지 못함.
        List<String> rows = jdbc.queryForList("SELECT result_text FROM ai_user_jobs WHERE id=? AND status='DONE' AND lease_owner=? AND fencing_token=?",
                String.class, id, owner, token);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(readOnly=true)
    public Map<String,Object> outcome(long id,String owner,long token,String result) {
        var row=jdbc.queryForMap("SELECT action_name,wait_reason FROM ai_user_jobs WHERE id=? AND status='DONE' AND lease_owner=? AND fencing_token=?",
                id,owner,token);
        var outcome=new java.util.LinkedHashMap<String,Object>();
        outcome.put("result",result);
        outcome.put("action",row.get("action_name"));
        outcome.put("waitReason",row.get("wait_reason"));
        return outcome;
    }

    @Transactional
    public void defer(long id,String owner,long token) {
        requireClaim(id,owner,token);
        jdbc.update("UPDATE ai_user_jobs SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END, "
                + "ready_at=? WHERE id=?",timestamp(clock.instant().plus(Duration.ofMinutes(30))),id);
        clearRuntime();
    }

    @Transactional
    public void fail(long id, String owner, long token) {
        requireClaim(id, owner, token);
        jdbc.update("UPDATE ai_user_jobs SET status='FAILED',finished_at=? WHERE id=?", timestamp(clock.instant()), id);
        clearRuntime();
    }

    @Transactional
    public void setEnabled(boolean enabled) {
        var runtime=lockRuntime();
        if (!enabled && runtime.get("current_job_id")!=null) {
            jdbc.update("UPDATE ai_user_jobs SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END, "
                    + "finished_at=CASE WHEN attempts>=3 THEN ? ELSE NULL END,ready_at=? WHERE id=? AND status='RUNNING'",
                    timestamp(clock.instant()),timestamp(clock.instant()),runtime.get("current_job_id"));
            clearRuntime();
            jdbc.update("UPDATE ai_user_runtime SET fencing_token=fencing_token+1 WHERE id=1");
        }
        jdbc.update("UPDATE ai_user_runtime SET enabled=? WHERE id=1", enabled);
        jdbc.update("UPDATE ai_user_profiles SET enabled=?", enabled);
    }

    @Transactional
    public void schedule() {
        if (!properties.enabled()) return;
        var runtime = lockRuntime();
        if (!Boolean.TRUE.equals(runtime.get("enabled"))) return;
        Instant now = clock.instant();
        var rows = jdbc.queryForList("SELECT user_id,ai_key,next_run_at,next_social_at FROM ai_user_profiles WHERE enabled=TRUE");
        int hour=now.atZone(ZoneId.of("Asia/Seoul")).getHour();
        var dayStart=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        for (var row : rows) {
            long userId = ((Number) row.get("user_id")).longValue();
            String persona=(String)row.get("ai_key");
            var slot = ((Timestamp) row.get("next_run_at")).toInstant();
            if (!slot.isAfter(now)) {
                if (AiPersonas.isAwake(persona,hour))
                    enqueue(userId, "schedule:" + userId + ":" + slot.toEpochMilli(), 10, "SCHEDULE", null);
                jdbc.update("UPDATE ai_user_profiles SET next_run_at=? WHERE user_id=?",
                        timestamp(now.plus(properties.scheduleInterval()).plusSeconds(userId % 10 * 30)), userId);
            }
            var social=row.get("next_social_at")==null?AiPersonas.nextSocialVisit(persona,userId,now.minus(Duration.ofMinutes(30))):
                    ((Timestamp)row.get("next_social_at")).toInstant();
            if (!social.isAfter(now)) {
                int visits=jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE user_id=? AND source_type='SOCIAL_BROWSE' AND created_at>=?",
                        Integer.class,userId,timestamp(dayStart));
                if (AiPersonas.isAwake(persona,hour) && visits<AiPersonas.socialVisits(persona).size())
                    enqueue(userId,"social:"+userId+":"+social.toEpochMilli(),20,"SOCIAL_BROWSE",null);
            }
            if (row.get("next_social_at")==null || !social.isAfter(now))
                jdbc.update("UPDATE ai_user_profiles SET next_social_at=? WHERE user_id=?",
                        timestamp(AiPersonas.nextSocialVisit(persona,userId,now)),userId);
        }
        jdbc.update("DELETE FROM ai_user_memories WHERE expires_at<=?", timestamp(now));
    }

    @Transactional
    public void bringForwardSocialVisit(Job job,AiUserActionExecutor.Action action) {
        if (!java.util.Set.of(AiUserActionExecutor.Action.SHOP_PURCHASE,AiUserActionExecutor.Action.ROOM_LAYOUT,
                AiUserActionExecutor.Action.MARKET_ORDER,AiUserActionExecutor.Action.MARKET_ISSUE).contains(action)) return;
        String persona=jdbc.queryForObject("SELECT ai_key FROM ai_user_profiles WHERE user_id=?",String.class,job.userId());
        if (!persona.startsWith("decorate-")) return;
        var now=clock.instant();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_jobs WHERE user_id=? AND source_type='SOCIAL_BROWSE' AND created_at>?",
                Integer.class,job.userId(),timestamp(now.minus(Duration.ofHours(2))))>0) return;
        var next=timestamp(now.plus(Duration.ofMinutes(20)));
        jdbc.update("UPDATE ai_user_profiles SET next_social_at=? WHERE user_id=? AND (next_social_at IS NULL OR next_social_at>?)",next,job.userId(),next);
    }

    private Map<String, Object> lockRuntime() {
        return jdbc.queryForMap("SELECT * FROM ai_user_runtime WHERE id=1 FOR UPDATE");
    }
    private void clearRuntime() {
        jdbc.update("UPDATE ai_user_runtime SET current_job_id=NULL,lease_owner=NULL,lease_until=NULL WHERE id=1");
    }
    private static void requireOwner(String owner) {
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid worker owner");
    }
    private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
    static Timestamp timestamp(Instant time) { return Timestamp.from(time.truncatedTo(ChronoUnit.MICROS)); }
}
