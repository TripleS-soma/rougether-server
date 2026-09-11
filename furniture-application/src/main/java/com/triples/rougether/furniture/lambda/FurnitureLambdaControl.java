package com.triples.rougether.furniture.lambda;

import static com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.entity.FurnitureLambdaExecution;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** 짧은 DB 트랜잭션 전용. S3나 AI 또는 Lambda Invoke를 호출하지 않음. */
@Service
@RequiredArgsConstructor
public class FurnitureLambdaControl {
    private final FurnitureWorkerCapacityRepository capacity;
    private final FurnitureGenerationJobRepository jobs;
    private final FurnitureLambdaExecutionRepository executions;
    private final UserRepository users;
    private final FurnitureGenerationTransactions transactions;
    private final Clock clock;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Reply handle(Command command) {
        validate(command);
        // 잠금 순서: capacity → user → job → execution. 모든 네트워크는 커밋 이후 실행함.
        var limit = capacity.lock().orElseThrow();
        var ownerId = jobs.findOwnerId(command.jobId()).orElse(null);
        if (ownerId == null) return Reply.of(Status.TERMINAL);
        users.findByIdForUpdate(ownerId).orElseThrow();
        var job = jobs.findForUpdate(command.jobId()).orElseThrow();
        var execution = executions.lock(command.executionId()).orElse(null);
        if (execution == null || !execution.getJobId().equals(job.getId())
                || !command.executionId().equals(job.getExecutionId())) return Reply.of(Status.STALE);
        String hash = hash(JSON.writeValueAsString(command));
        if (execution.getOwner() != null && !execution.getOwner().equals(command.owner())) {
            return Reply.of(execution.getState() == FurnitureLambdaExecution.State.FINISHED
                    ? Status.TERMINAL : Status.DUPLICATE);
        }
        if (execution.getDeadline() != null && !execution.getDeadline().isAfter(clock.instant())) {
            transactions.maintain(job.getId()); execution.finish();
            return Reply.of(Status.TERMINAL);
        }
        transactions.maintain(job.getId());
        if (job.terminal()) { execution.finish(); return Reply.of(Status.TERMINAL); }
        if (execution.getSequence() == command.sequence()) {
            if (!hash.equals(execution.getCommandHash())) throw new IllegalArgumentException("같은 단계의 명령 내용이 다름");
            return JSON.readValue(execution.getReplyJson(), Reply.class);
        }
        if (execution.getState() == FurnitureLambdaExecution.State.FINISHED
                || command.sequence() != execution.getSequence() + 1) return Reply.of(Status.STALE);
        if (command.sequence() == 0) {
            if (!"LAMBDA".equals(limit.getExecutionMode()) || !limit.isExecutionEnabled()
                    || jobs.countByStatus(FurnitureGenerationJob.Status.PROCESSING) >= limit.getMaxInFlight()) {
                return Reply.of(Status.BUSY);
            }
            // Lambda 600초 + 정리 여유 60초. 단계별 큐로 되돌리지 않고 한 실행이 계속 소유함.
            execution.start(command.owner(), clock.instant().plusSeconds(660));
        } else {
            var previous = JSON.readValue(execution.getReplyJson(), Reply.class);
            if (previous.status() != Status.READY || !job.ownsLease(previous.claim().token(), clock.instant())) {
                execution.finish(); return Reply.of(Status.STALE);
            }
            apply(previous.claim(), command.result(), command.executionId());
        }
        var claim = job.terminal() ? null : transactions.claimForLambda(job.getId(), execution.getDeadline());
        Reply reply = claim == null ? Reply.of(Status.TERMINAL) : new Reply(Status.READY, claim);
        if (claim == null) execution.finish();
        execution.remember(command.sequence(), hash, JSON.writeValueAsString(reply));
        return reply;
    }

    private void apply(FurnitureGenerationTransactions.Claim claim, Result result, String executionId) {
        if (result.action() != claim.action()) throw new IllegalArgumentException("단계 불일치");
        if (result.failureCode() != null) {
            if (!result.failureCode().matches("[A-Z_]{1,50}")) throw new IllegalArgumentException("실패 코드 오류");
            transactions.failed(claim, result.failureCode()); return;
        }
        if (result.inputTokens() < 0 || result.outputTokens() < 0) throw new IllegalArgumentException("토큰 사용량 오류");
        switch (claim.action()) {
            case EXTRACT -> {
                var extracted = Objects.requireNonNull(result.extracted());
                if (extracted.inputTokens() < 0 || extracted.outputTokens() < 0
                        || (extracted.furniture() && (extracted.subjectJson() == null || extracted.subjectJson().length() > 2500))) {
                    throw new IllegalArgumentException("특징 추출 결과 오류");
                }
                transactions.extracted(claim, extracted);
            }
            case REVIEW -> {
                var review = Objects.requireNonNull(result.review());
                Objects.requireNonNull(review.decision());
                if (review.inputTokens() < 0 || review.outputTokens() < 0
                        || tooLong(review.name(), 100) || tooLong(review.reason(), 1000) || tooLong(review.correction(), 2000)) {
                    throw new IllegalArgumentException("검수 결과 오류");
                }
                if (result.assetKey() != null && !result.assetKey().equals(claim.resultAssetKey())) {
                    requireKey(result.assetKey(), "items/photo-furniture/furniture/" + executionId + "/");
                }
                transactions.reviewed(claim, review, result.hardPass(), result.assetKey());
            }
            default -> {
                requireKey(result.assetKey(), "private/furniture-generation/" + claim.id() + "/" + executionId + "/candidate/");
                transactions.generated(claim, result.assetKey(), result.inputTokens(), result.outputTokens());
            }
        }
    }
    private static boolean tooLong(String value, int max) { return value != null && value.length() > max; }
    private static void requireKey(String key, String prefix) {
        if (key == null || !key.startsWith(prefix) || key.length() > 255 || key.contains("..") || key.contains(":") || key.contains("\\")) {
            throw new IllegalArgumentException("실행 에셋 경로 오류");
        }
    }
    private static void validate(Command c) {
        Objects.requireNonNull(c);
        UUID.fromString(c.jobId()); UUID.fromString(c.executionId());
        if (c.owner() == null || !c.owner().matches("[A-Za-z0-9_-]{1,100}") || c.sequence() < 0 || c.sequence() > 32
                || (c.sequence() == 0) != (c.result() == null)) throw new IllegalArgumentException("실행 명령 오류");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
