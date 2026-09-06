package com.triples.rougether.userapi.furniture.service;

import static com.triples.rougether.userapi.furniture.error.FurnitureGenerationErrorCode.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.billing.service.FurnitureCreditTransactions;
import com.triples.rougether.domain.furniture.entity.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.*;
import com.triples.rougether.domain.shop.repository.*;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.userapi.furniture.dto.FurnitureGenerationResponse;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사용자 → 작업 순으로 잠금. 네트워크 호출은 이 서비스 밖에서 수행함.
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
    private final FurnitureCreditTransactions credits;

    public record Reservation(FurnitureGenerationResponse job, boolean created) { }
    public record Claim(String id, Long userId, String token, Action action, String sourceKey,
                        String candidateKey, String resultAssetKey, String targetHint, String feedback,
                        String correction, String subjectJson) { }
    public record Cleanup(String id, String sourceKey, String candidateKey) { }

    public Reservation reserve(Long userId, String requestId, String digest, String hint) {
        activeUser(userId);
        var existing = jobs.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) {
            var job = existing.get();
            if (!job.getInputDigest().equals(digest)) throw new BusinessException(FURNITURE_REQUEST_CONFLICT);
            return new Reservation(FurnitureGenerationResponse.from(job), false);
        }
        requireNoActiveJob(userId);
        Instant now = clock.instant();
        Instant dayStart = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
                .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        if (jobs.countByUserIdAndCreatedAtGreaterThanEqual(userId, dayStart) >= config.dailyLimit()) {
            throw new BusinessException(FURNITURE_DAILY_LIMIT);
        }
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

    public FurnitureGenerationResponse feedback(Long userId, String id, String requestId, String content) {
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
        if (job.getReviewAttempts() >= config.maxReviewAttempts()) throw new BusinessException(FURNITURE_BUDGET_EXHAUSTED);
        feedbacks.save(new FurnitureGenerationFeedback(id, requestId, content, clock.instant()));
        job.requestReview(content, clock.instant());
        return FurnitureGenerationResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<String> due() { return jobs.findDue(clock.instant(), PageRequest.of(0, 10)); }

    public Claim claim(String id) {
        User user = lockedUser(jobs.findOwnerId(id).orElseThrow());
        var job = jobs.findForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        if (!job.claimable(now)) return null;
        if (user.getDeletedAt() != null || expired(job)) {
            fail(job, user.getDeletedAt() != null ? "OWNER_WITHDRAWN" : "SOURCE_EXPIRED", now);
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
        String token = job.claim(now, now.plus(config.timeout()).plusSeconds(60));
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
        return jobs.findForMaintenance(now, now.minusSeconds(300), PageRequest.of(0, 50));
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
        } else if (job.getStatus() == Status.UPLOADING && job.getCreatedAt().plusSeconds(300).isBefore(now)) {
            fail(job, "UPLOAD_INTERRUPTED", now);
        }
        if (user.getDeletedAt() != null || expired(job)) {
            return new Cleanup(id, job.getSourceKey(), job.getCandidateKey());
        }
        return null;
    }

    public void cleaned(Cleanup cleanup) {
        var job = jobs.findForUpdate(cleanup.id()).orElseThrow();
        if (Objects.equals(job.getSourceKey(), cleanup.sourceKey())
                && Objects.equals(job.getCandidateKey(), cleanup.candidateKey())) {
            job.clearPrivateAssets();
            feedbacks.deleteByJobId(job.getId());
        }
    }

    @Transactional(readOnly = true)
    public boolean referencesAsset(String key) { return jobs.referencesAsset(key); }

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
}
