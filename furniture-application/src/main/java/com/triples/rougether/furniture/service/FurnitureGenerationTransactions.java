package com.triples.rougether.furniture.service;

import static com.triples.rougether.furniture.error.FurnitureGenerationErrorCode.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol;

import com.triples.rougether.domain.furniture.entity.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.*;
import com.triples.rougether.domain.shop.repository.*;
import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 접수/claim은 전체 용량 → 사용자 → 작업 순으로 잠금. 네트워크 호출은 서비스 밖에서 수행함.
@Service
@RequiredArgsConstructor
@Transactional
public class FurnitureGenerationTransactions {
    private static final List<Status> ACTIVE = List.of(Status.UPLOADING, Status.QUEUED, Status.PROCESSING);
    private final FurnitureGenerationJobRepository jobs;
    private final FurnitureGenerationFeedbackRepository feedbacks;
    private final UserRepository users;
    private final ItemRepository items;
    private final ThemeRepository themes;
    private final UserItemRepository inventory;
    private final FurnitureGenerationProperties config;
    private final Clock clock;
    private final GenerationCreditLedger credits;
    private final FurnitureWorkerCapacityRepository capacity;
    private final FurnitureLambdaExecutionRepository executions;
    @org.springframework.beans.factory.annotation.Value("${furniture.admission.max-outstanding:40}")
    private int maxOutstanding = 40;
    @org.springframework.beans.factory.annotation.Value("${furniture.admission.max-queue-wait:5m}")
    private Duration maxQueueWait = Duration.ofMinutes(5);

    @jakarta.annotation.PostConstruct
    void validateAdmission() {
        if (maxOutstanding < 1 || maxOutstanding > 1000 || maxQueueWait.compareTo(Duration.ofSeconds(10)) < 0
                || maxQueueWait.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("가구 접수 한도 오류");
    }

    public record Reservation(FurnitureGenerationResponse job, boolean created) { }
    public record Claim(String id, Long userId, String token, Action action, String sourceKey,
                        String candidateKey, String resultAssetKey, String targetHint, String feedback,
                        String correction, String subjectJson) { }
    public record Cleanup(String id, String sourceKey, String candidateKey, String rawSourceKey) { }
    public record UploadReservation(FurnitureGenerationResponse job, String rawKey, String sha256,
                                    long bytes, String contentType, Instant expiresAt) { }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public UploadReservation reserveDirect(Long userId, String requestId, String sha256, long bytes, String contentType, String hint) {
        UUID.fromString(requestId);
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || bytes < 1 || bytes > 10 * 1024 * 1024
                || !("image/jpeg".equals(contentType) || "image/png".equals(contentType))
                || hint == null || hint.length() > 120) throw new BusinessException(FURNITURE_PHOTO_INVALID);
        String digest;
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            digest = HexFormat.of().formatHex(hash.digest(("direct\n" + sha256 + "\n" + bytes + "\n" + contentType + "\n" + hint)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var reservation = reserve(userId, requestId, digest, hint);
        var job = ownedForUpdate(userId, reservation.job().id());
        if (reservation.created()) {
            job.directUpload(FurniturePreprocessProtocol.sourceKey(job.getId()), sha256, bytes, contentType,
                    clock.instant().plus(Duration.ofMinutes(30)));
        }
        if (job.getUploadExpiresAt() == null) throw new BusinessException(FURNITURE_REQUEST_CONFLICT);
        if (job.getStatus() == Status.UPLOADING && !job.getUploadExpiresAt().isAfter(clock.instant())) {
            fail(job, "UPLOAD_INTERRUPTED", clock.instant());
        }
        return new UploadReservation(FurnitureGenerationResponse.from(job), job.getRawSourceKey(), sha256,
                bytes, contentType, job.getUploadExpiresAt());
    }

    // S3 네트워크 검증은 Lambda에서 수행함. 여기서는 사용자→작업 잠금 아래 버전과 상태만 전이함.
    public FurniturePreprocessProtocol.Reply preprocess(FurniturePreprocessProtocol.Command command) {
        var phase = Objects.requireNonNull(command.phase());
        UUID.fromString(command.jobId());
        if (!"PREPROCESS".equals(command.kind()) || !FurniturePreprocessProtocol.sourceKey(command.jobId()).equals(command.sourceKey())
                || command.sourceVersion() == null || command.sourceVersion().isBlank() || command.sourceVersion().length() > 1024
                || "null".equals(command.sourceVersion())) throw new IllegalArgumentException("전처리 원본 명령 오류");
        var owner = jobs.findOwnerId(command.jobId());
        if (owner.isEmpty()) return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.STALE);
        User user = lockedUser(owner.get());
        var job = ownedForUpdate(owner.get(), command.jobId());
        if (!Objects.equals(job.getRawSourceKey(), command.sourceKey())) return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.STALE);
        if (job.getSourceVersion() != null && !job.getSourceVersion().equals(command.sourceVersion())) {
            return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.STALE);
        }
        if (job.getStatus() != Status.UPLOADING) {
            return FurniturePreprocessProtocol.Reply.of(job.getStatus() == Status.FAILED
                    ? FurniturePreprocessProtocol.Status.TERMINAL : FurniturePreprocessProtocol.Status.DONE);
        }
        if (user.getDeletedAt() != null || expired(job) || !job.getUploadExpiresAt().isAfter(clock.instant())) {
            fail(job, user.getDeletedAt() != null ? "OWNER_WITHDRAWN" : "UPLOAD_INTERRUPTED", clock.instant());
            return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.TERMINAL);
        }
        if (phase == FurniturePreprocessProtocol.Phase.START) {
            job.bindSourceVersion(command.sourceVersion());
            return new FurniturePreprocessProtocol.Reply(FurniturePreprocessProtocol.Status.READY,
                    new FurniturePreprocessProtocol.Input(job.getSourceSha256(), job.getSourceBytes(), job.getSourceContentType()));
        }
        if (job.getSourceVersion() == null) return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.STALE);
        if (phase == FurniturePreprocessProtocol.Phase.FAIL) {
            if (!Set.of("FURNITURE_PHOTO_INVALID", "SOURCE_UPLOAD_MISMATCH").contains(command.failureCode())) {
                throw new IllegalArgumentException("전처리 실패 코드 오류");
            }
            fail(job, command.failureCode(), clock.instant());
            return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.TERMINAL);
        }
        if (!FurniturePreprocessProtocol.outputKey(job.getId()).equals(command.outputKey())
                || command.outputVersion() == null || command.outputVersion().isBlank() || command.outputVersion().length() > 1024
                || "null".equals(command.outputVersion()) || command.pngSha256() == null
                || !command.pngSha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("전처리 출력 명령 오류");
        job.prepared(command.outputVersion(), command.pngSha256());
        job.uploaded(command.outputKey(), clock.instant());
        enqueueExecution(job);
        return FurniturePreprocessProtocol.Reply.of(FurniturePreprocessProtocol.Status.DONE);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public Reservation reserve(Long userId, String requestId, String digest, String hint) {
        capacity.lock().orElseThrow();
        activeUser(userId);
        var existing = jobs.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) {
            var job = existing.get();
            if (!job.getInputDigest().equals(digest)) throw new BusinessException(FURNITURE_REQUEST_CONFLICT);
            return new Reservation(FurnitureGenerationResponse.from(job), false);
        }
        requireNoActiveJob(userId);
        requireQueueSpace();
        Instant now = clock.instant();
        var job = jobs.save(FurnitureGenerationJob.create(userId, requestId, digest, hint, now, now.plus(config.retention())));
        credits.reserve(userId, job.getId());
        return new Reservation(FurnitureGenerationResponse.from(job), true);
    }

    public boolean uploaded(Long userId, String id, String sourceKey) {
        User user = lockedUser(userId);
        var job = ownedForUpdate(userId, id);
        if (user.getDeletedAt() != null || job.getStatus() != Status.UPLOADING || expired(job)) {
            fail(job, "UPLOAD_INTERRUPTED", clock.instant());
            return false;
        }
        job.uploaded(sourceKey, clock.instant());
        enqueueExecution(job);
        return true;
    }

    public void uploadFailed(Long userId, String id) {
        lockedUser(userId);
        var job = ownedForUpdate(userId, id);
        if (job.getStatus() == Status.UPLOADING) fail(job, "SOURCE_UPLOAD_FAILED", clock.instant());
    }

    @Transactional(readOnly = true)
    public FurnitureGenerationResponse get(Long userId, String id) {
        requireActiveUser(userId);
        return FurnitureGenerationResponse.from(jobs.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(FURNITURE_JOB_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public List<FurnitureGenerationResponse> list(Long userId) {
        requireActiveUser(userId);
        return jobs.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).stream()
                .map(FurnitureGenerationResponse::from).toList();
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public FurnitureGenerationResponse feedback(Long userId, String id, String requestId, String content) {
        capacity.lock().orElseThrow();
        activeUser(userId);
        var job = ownedForUpdate(userId, id);
        if (expired(job) || job.getSourceKey() == null) throw new BusinessException(FURNITURE_SOURCE_EXPIRED);
        var previous = feedbacks.findByJobIdAndRequestId(id, requestId);
        if (previous.isPresent()) {
            if (!previous.get().getContent().equals(content)) throw new BusinessException(FURNITURE_REQUEST_CONFLICT);
            return FurnitureGenerationResponse.from(job);
        }
        if (job.getStatus() != Status.SUCCEEDED) throw new BusinessException(FURNITURE_FEEDBACK_UNAVAILABLE);
        requireNoActiveJob(userId);
        requireQueueSpace();
        if (job.getReviewAttempts() >= config.maxReviewAttempts()) throw new BusinessException(FURNITURE_BUDGET_EXHAUSTED);
        feedbacks.save(new FurnitureGenerationFeedback(id, requestId, content, clock.instant()));
        job.requestReview(content, clock.instant());
        enqueueExecution(job);
        return FurnitureGenerationResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<String> due() { return jobs.findDue(clock.instant(), PageRequest.of(0, 10)); }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Claim claim(String id) {
        // 전체 용량 → 사용자 → 작업 순으로 잠금. 잠금은 외부 호출 전에 해제됨.
        var limit = capacity.lock().orElseThrow();
        if (!"RESIDENT".equals(limit.getExecutionMode()) || !limit.isExecutionEnabled()
                || jobs.countByStatus(Status.PROCESSING) >= limit.getMaxInFlight()) return null;
        return claimStage(id, null);
    }

    // 호출자는 capacity 잠금을 보유하며 실행 소유권과 전체 제한을 먼저 검증해야 함.
    public Claim claimForLambda(String id, Instant deadline) {
        return claimStage(id, deadline);
    }

    private Claim claimStage(String id, Instant deadline) {
        User user = lockedUser(jobs.findOwnerId(id).orElseThrow());
        var job = jobs.findForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        if (!job.claimable(now)) return null;
        if (user.getDeletedAt() != null || expired(job)) {
            fail(job, user.getDeletedAt() != null ? "OWNER_WITHDRAWN" : "SOURCE_EXPIRED", now);
            return null;
        }
        if (job.getNextRunAt().plus(maxQueueWait).compareTo(now) <= 0) {
            fail(job, "QUEUE_WAIT_EXCEEDED", now);
            return null;
        }
        // 이전 버전에서 접수된 작업도 생성 전에 특징 추출을 거치도록 함.
        if (job.getAction() != Action.REVIEW && job.getSubjectJson() == null && job.getExtractionAttempts() == 0) {
            job.enqueue(Action.EXTRACT, now);
        }
        boolean extract = job.getAction() == Action.EXTRACT;
        boolean review = job.getAction() == Action.REVIEW;
        if ((extract && job.getExtractionAttempts() >= 1)
                || (review && job.getReviewAttempts() >= config.maxReviewAttempts())
                || (!review && !extract && (job.getImageAttempts() >= config.maxImageAttempts()
                    || job.getReviewAttempts() >= config.maxReviewAttempts()))) {
            fail(job, "GENERATION_BUDGET_EXHAUSTED", now);
            return null;
        }
        String token = job.claim(now, deadline == null ? now.plus(config.timeout()).plusSeconds(60) : deadline);
        return new Claim(job.getId(), job.getUserId(), token, job.getAction(), job.getSourceKey(),
                job.getCandidateKey(), job.getResultAssetKey(), job.getTargetHint(), job.getFeedback(),
                job.getCorrectionPrompt(), job.getSubjectJson());
    }

    public boolean extracted(Claim claim, FurnitureAiClient.Extracted extracted) {
        var job = leased(claim);
        if (job == null || job.getAction() != Action.EXTRACT) return false;
        job.recordUsage(extracted.inputTokens(), extracted.outputTokens());
        if (!extracted.furniture()) fail(job, "PHOTO_REJECTED", clock.instant());
        else job.extracted(extracted.subjectJson(), clock.instant());
        return true;
    }

    public boolean generated(Claim claim, String key, long input, long output) {
        var job = leased(claim);
        if (job == null || job.getAction() == Action.EXTRACT || job.getAction() == Action.REVIEW) return false;
        job.recordUsage(input, output);
        job.generated(key, clock.instant());
        return true;
    }

    public boolean reviewed(Claim claim, FurnitureAiClient.Review review, boolean hardPass, String finalKey) {
        var job = leased(claim);
        if (job == null || job.getAction() != Action.REVIEW) return false;
        job.recordUsage(review.inputTokens(), review.outputTokens());
        job.reviewed(review.decision().name(), review.reason(), review.correction());
        Instant now = clock.instant();
        switch (review.decision()) {
            case ACCEPT -> {
                if (!hardPass || finalKey == null) {
                    fail(job, "HARD_QA_REJECTED", now);
                    return false;
                }
                Long inventoryId = job.getUserItemId();
                if (inventoryId == null) {
                    Theme theme = themes.findByCode("photo_furniture").orElseThrow();
                    Item item = items.save(new Item(theme, "furniture", "positioned", null, null,
                            review.name(), null, null, finalKey, false, false));
                    item.updateDefaultSlot("midRight");
                    inventoryId = inventory.save(UserItem.create(users.getReferenceById(job.getUserId()), item)).getId();
                } else {
                    UserItem owned = inventory.findOwnedWithItem(job.getUserId(), inventoryId).orElseThrow();
                    if (items.replaceAssetKeyIfUnchanged(owned.getItem().getId(), job.getResultAssetKey(), finalKey) != 1) {
                        fail(job, "RESULT_CHANGED_EXTERNALLY", now);
                        return false;
                    }
                }
                credits.settle(job.getUserId(), job.getId(), true);
                job.succeed(finalKey, inventoryId, now);
                return true;
            }
            case EDIT, REGENERATE -> {
                if (job.getImageAttempts() >= config.maxImageAttempts()
                        || job.getReviewAttempts() >= config.maxReviewAttempts()) fail(job, "GENERATION_BUDGET_EXHAUSTED", now);
                else job.enqueue(review.decision() == FurnitureAiClient.Decision.EDIT ? Action.EDIT : Action.REGENERATE, now);
            }
            case REJECT -> fail(job, "PHOTO_REJECTED", now);
        }
        return false;
    }

    public void failed(Claim claim, String code) {
        var job = leased(claim);
        if (job != null) fail(job, code, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<String> maintenanceCandidates() {
        Instant now = clock.instant();
        return jobs.findForMaintenance(now, now.minusSeconds(300), now.minus(maxQueueWait), PageRequest.of(0, 50));
    }

    public Cleanup maintain(String id) {
        User user = lockedUser(jobs.findOwnerId(id).orElseThrow());
        var job = jobs.findForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        if (user.getDeletedAt() != null) fail(job, "OWNER_WITHDRAWN", now);
        else if (!job.terminal() && expired(job)) fail(job, "SOURCE_EXPIRED", now);
        else if (job.getStatus() == Status.PROCESSING && !job.getLeaseUntil().isAfter(now)) {
            // 결과를 모르는 외부 호출은 자동 재전송하지 않음. 늦게 도착한 응답은 lease fencing으로 무시함.
            fail(job, "WORKER_INTERRUPTED", now);
        } else if (job.getStatus() == Status.UPLOADING && !(job.getUploadExpiresAt() == null
                ? job.getCreatedAt().plusSeconds(300) : job.getUploadExpiresAt()).isAfter(now)) {
            fail(job, "UPLOAD_INTERRUPTED", now);
        } else if (job.getStatus() == Status.QUEUED && !job.getNextRunAt().plus(maxQueueWait).isAfter(now)) {
            fail(job, "QUEUE_WAIT_EXCEEDED", now);
        }
        if (user.getDeletedAt() != null || expired(job)) {
            return new Cleanup(id, job.getSourceKey(), job.getCandidateKey(), job.getRawSourceKey());
        }
        return null;
    }

    public void cleaned(Cleanup cleanup) {
        var job = jobs.findForUpdate(cleanup.id()).orElseThrow();
        if (Objects.equals(job.getSourceKey(), cleanup.sourceKey())
                && Objects.equals(job.getRawSourceKey(), cleanup.rawSourceKey())
                && Objects.equals(job.getCandidateKey(), cleanup.candidateKey())) {
            job.clearPrivateAssets();
            feedbacks.deleteByJobId(job.getId());
            executions.deleteByJobId(job.getId());
        }
    }

    @Transactional(readOnly = true)
    public boolean referencesAsset(String key) { return jobs.referencesAsset(key); }

    private void enqueueExecution(FurnitureGenerationJob job) {
        var execution = executions.save(new FurnitureLambdaExecution(job.getId(), clock.instant()));
        job.assignExecution(execution.getId());
    }

    private FurnitureGenerationJob leased(Claim claim) {
        User user = lockedUser(claim.userId());
        var job = ownedForUpdate(claim.userId(), claim.id());
        if (!job.ownsLease(claim.token(), clock.instant())) return null;
        if (user.getDeletedAt() != null || expired(job)) {
            fail(job, user.getDeletedAt() != null ? "OWNER_WITHDRAWN" : "SOURCE_EXPIRED", clock.instant());
            return null;
        }
        return job;
    }

    private FurnitureGenerationJob ownedForUpdate(Long userId, String id) {
        return jobs.findForUpdate(id).filter(j -> j.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(FURNITURE_JOB_NOT_FOUND));
    }
    private User lockedUser(Long userId) {
        return users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
    }
    private void activeUser(Long userId) {
        if (lockedUser(userId).getDeletedAt() != null) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
    }
    private void requireActiveUser(Long userId) {
        users.findByIdAndDeletedAtIsNull(userId).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
    }
    private void fail(FurnitureGenerationJob job, String code, Instant now) {
        credits.settle(job.getUserId(), job.getId(), false);
        job.fail(code, now);
    }
    private boolean expired(FurnitureGenerationJob job) { return !job.getExpiresAt().isAfter(clock.instant()); }
    private void requireNoActiveJob(Long userId) {
        if (jobs.existsByUserIdAndStatusIn(userId, ACTIVE)) throw new BusinessException(FURNITURE_JOB_IN_PROGRESS);
    }
    private void requireQueueSpace() {
        // 같은 capacity 행의 잠금으로 여러 API replica의 동시 접수도 합산함.
        if (jobs.countByStatusIn(ACTIVE) >= maxOutstanding) throw new BusinessException(FURNITURE_QUEUE_FULL);
    }
}
