package com.triples.rougether.domain.furniture.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "furniture_generation_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FurnitureGenerationJob {
    public enum Status { UPLOADING, QUEUED, PROCESSING, SUCCEEDED, FAILED }
    public enum Action { GENERATE, EDIT, REGENERATE, REVIEW }

    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 36) private String requestId;
    @Column(nullable = false, length = 64) private String inputDigest;
    @Column(nullable = false, length = 120) private String targetHint;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Action action;
    @Column(length = 255) private String sourceKey;
    @Column(length = 255) private String candidateKey;
    @Column(length = 255) private String resultAssetKey;
    private Long userItemId;
    @Column(nullable = false, length = 500) private String feedback = "";
    @Column(nullable = false, length = 2000) private String correctionPrompt = "";
    @Column(length = 30) private String lastDecision;
    @Column(length = 1000) private String lastReason;
    @Column(length = 50) private String failureCode;
    @Column(nullable = false) private int imageAttempts;
    @Column(nullable = false) private int reviewAttempts;
    @Column(nullable = false) private long inputTokens;
    @Column(nullable = false) private long outputTokens;
    @Column(length = 36) private String leaseToken;
    private Instant leaseUntil;
    @Column(nullable = false) private Instant nextRunAt;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    public static FurnitureGenerationJob create(Long userId, String requestId, String digest,
                                                String hint, Instant now, Instant expiresAt) {
        var job = new FurnitureGenerationJob();
        job.id = UUID.randomUUID().toString();
        job.userId = userId;
        job.requestId = requestId;
        job.inputDigest = digest;
        job.targetHint = hint;
        job.status = Status.UPLOADING;
        job.action = Action.GENERATE;
        job.createdAt = now;
        job.updatedAt = now;
        job.nextRunAt = now;
        job.expiresAt = expiresAt;
        return job;
    }

    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.FAILED; }

    public void uploaded(String key, Instant now) {
        sourceKey = key;
        enqueue(Action.GENERATE, now);
    }

    public boolean claimable(Instant now) {
        return status == Status.QUEUED && !nextRunAt.isAfter(now);
    }

    public String claim(Instant now, Instant leaseEnd) {
        if (!claimable(now)) throw new IllegalStateException("실행 가능한 작업이 아님");
        status = Status.PROCESSING;
        leaseToken = UUID.randomUUID().toString();
        leaseUntil = leaseEnd;
        updatedAt = now;
        if (action == Action.REVIEW) reviewAttempts++; else imageAttempts++;
        return leaseToken;
    }

    public boolean ownsLease(String token, Instant now) {
        return status == Status.PROCESSING && token.equals(leaseToken) && leaseUntil.isAfter(now);
    }

    public void generated(String key, Instant now) {
        candidateKey = key;
        enqueue(Action.REVIEW, now);
    }

    public void reviewed(String decision, String reason, String correction) {
        lastDecision = decision;
        lastReason = reason;
        correctionPrompt = correction;
    }

    public void enqueue(Action nextAction, Instant now) {
        status = Status.QUEUED;
        action = nextAction;
        nextRunAt = now;
        updatedAt = now;
        leaseToken = null;
        leaseUntil = null;
    }

    public void succeed(String assetKey, Long inventoryId, Instant now) {
        resultAssetKey = assetKey;
        userItemId = inventoryId;
        status = Status.SUCCEEDED;
        failureCode = null;
        updatedAt = now;
        leaseToken = null;
        leaseUntil = null;
    }

    public void requestReview(String content, Instant now) {
        feedback = content;
        candidateKey = resultAssetKey;
        enqueue(Action.REVIEW, now);
    }

    public void fail(String code, Instant now) {
        status = Status.FAILED;
        failureCode = code;
        updatedAt = now;
        leaseToken = null;
        leaseUntil = null;
    }

    public void recordUsage(long input, long output) {
        inputTokens += Math.max(0, input);
        outputTokens += Math.max(0, output);
    }

    public void clearPrivateAssets() {
        sourceKey = null;
        candidateKey = null;
        feedback = "";
        correctionPrompt = "";
        targetHint = "";
    }
}
