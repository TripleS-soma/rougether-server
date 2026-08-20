package com.triples.rougether.adminapi.assetfoundry.service;

import com.triples.rougether.adminapi.asset.AssetKeyClassifier;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineCreateRequest;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineJobResponse;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineListResponse;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineQaCheckRequest;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineQaUpdateRequest;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineStatusUpdateRequest;
import com.triples.rougether.adminapi.assetfoundry.error.AssetFoundryException;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineCheck;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineCheckResult;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineJob;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineKind;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineStatus;
import com.triples.rougether.domain.assetpipeline.repository.AssetPipelineJobRepository;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AssetFoundryService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,99}$");
    private static final Pattern ASSET_KEY_PATTERN = Pattern.compile(
            "^(?:items|characters|house)/[a-z0-9_./-]+\\.(?:png|jpe?g|webp)$");
    private static final Pattern CHARACTER_ANIMATION_PATTERN = Pattern.compile(
            "^characters/[^/]+/animations/(?:idle|pose-cycle|wave)\\.webp$");
    private static final Pattern HOUSE_FRAME_PATTERN = Pattern.compile(
            "^house/[a-z0-9_./-]*frame[a-z0-9_./-]*\\.png$");

    private final AssetPipelineJobRepository jobRepository;

    public AssetFoundryService(AssetPipelineJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public AssetPipelineJobResponse create(AssetPipelineCreateRequest request, String actor) {
        validateCreateRequest(request);
        String slug = request.slug().trim().toLowerCase(Locale.ROOT);
        if (jobRepository.existsBySlug(slug)) {
            throw error("ASSET_PIPELINE_SLUG_CONFLICT", "이미 사용 중인 작업 slug입니다: " + slug,
                    HttpStatus.CONFLICT);
        }
        AssetPipelineJob job = new AssetPipelineJob(
                request.name().trim(),
                slug,
                request.kind(),
                trimToNull(request.themeCode()),
                trimToNull(request.sourceAssetKey()),
                request.outputAssetKey().trim(),
                trimToNull(request.previewAssetKey()),
                request.targetItemId(),
                trimToNull(request.expectedOldAssetKey()),
                actor);
        return AssetPipelineJobResponse.from(jobRepository.saveAndFlush(job));
    }

    public AssetPipelineListResponse list() {
        List<AssetPipelineJobResponse> items = jobRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(AssetPipelineJobResponse::from)
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.stream(AssetPipelineStatus.values())
                .forEach(status -> counts.put(status.name(), 0L));
        items.forEach(item -> counts.compute(item.status().name(), (key, count) -> count + 1));
        return new AssetPipelineListResponse(items, counts);
    }

    @Transactional
    public AssetPipelineJobResponse updateStatus(Long jobId,
                                                 AssetPipelineStatusUpdateRequest request,
                                                 String actor) {
        AssetPipelineJob job = findJob(jobId);
        validateRevision(job, request.expectedRevision());
        AssetPipelineStatus target = request.targetStatus();
        String note = trimToNull(request.note());
        if (target == null || note == null) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", "목표 단계와 변경 사유가 필요합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        if (!job.getStatus().canMoveTo(target)) {
            throw error("ASSET_PIPELINE_TRANSITION_INVALID",
                    job.getStatus() + "에서 " + target + " 단계로 이동할 수 없습니다.",
                    HttpStatus.BAD_REQUEST);
        }
        if (target == AssetPipelineStatus.VALIDATED && !job.isQaPassed()) {
            throw error("ASSET_PIPELINE_QA_REQUIRED", "필수 품질 게이트를 모두 통과해야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        job.moveTo(target, actor, note);
        jobRepository.flush();
        return AssetPipelineJobResponse.from(job);
    }

    @Transactional
    public AssetPipelineJobResponse updateQa(Long jobId,
                                             AssetPipelineQaUpdateRequest request,
                                             String actor) {
        AssetPipelineJob job = findJob(jobId);
        validateRevision(job, request.expectedRevision());
        if (job.getStatus() != AssetPipelineStatus.BUILT
                && job.getStatus() != AssetPipelineStatus.VALIDATED) {
            throw error("ASSET_PIPELINE_QA_STATUS_INVALID",
                    "QA 리포트는 BUILT 또는 VALIDATED 단계에서만 반영할 수 있습니다.",
                    HttpStatus.BAD_REQUEST);
        }
        List<AssetPipelineQaCheckRequest> requests = request.checks();
        if (requests == null || requests.isEmpty()) {
            throw error("ASSET_PIPELINE_QA_INVALID", "QA 체크가 한 개 이상 필요합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        Set<String> codes = requests.stream()
                .map(AssetPipelineQaCheckRequest::code)
                .filter(code -> code != null)
                .collect(Collectors.toSet());
        if (codes.size() != requests.size()) {
            throw error("ASSET_PIPELINE_QA_INVALID", "QA check code는 비어 있거나 중복될 수 없습니다.",
                    HttpStatus.BAD_REQUEST);
        }

        List<AssetPipelineCheck> checks = requests.stream()
                .map(check -> toCheck(job, check))
                .toList();
        job.replaceChecks(checks);
        job.recordEvent(actor, "자동 QA 리포트 반영 · " + checks.size() + "개 게이트");
        jobRepository.flush();
        return AssetPipelineJobResponse.from(job);
    }

    private AssetPipelineJob findJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> error("ASSET_PIPELINE_NOT_FOUND",
                        "에셋 파이프라인 작업을 찾을 수 없습니다: " + jobId,
                        HttpStatus.NOT_FOUND));
    }

    private static AssetPipelineCheck toCheck(AssetPipelineJob job, AssetPipelineQaCheckRequest check) {
        String code = trimToNull(check.code());
        String label = trimToNull(check.label());
        AssetPipelineCheckResult result = check.result();
        if (code == null || code.length() > 60 || label == null || label.length() > 100 || result == null) {
            throw error("ASSET_PIPELINE_QA_INVALID", "QA code, label, result를 확인하세요.",
                    HttpStatus.BAD_REQUEST);
        }
        if (check.score() != null && (check.score() < 0 || check.score() > 100)) {
            throw error("ASSET_PIPELINE_QA_INVALID", "QA 점수는 0 이상 100 이하여야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        String message = trimToNull(check.message());
        if (message != null && message.length() > 500) {
            throw error("ASSET_PIPELINE_QA_INVALID", "QA 설명은 500자 이하여야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        return new AssetPipelineCheck(job, code, label, result, check.score(), check.required(), message);
    }

    private static void validateRevision(AssetPipelineJob job, Long expectedRevision) {
        if (expectedRevision == null || job.getRevision() != expectedRevision) {
            throw error("ASSET_PIPELINE_REVISION_CONFLICT",
                    "작업이 다른 화면에서 변경되었습니다. 새로고침 후 다시 시도하세요.",
                    HttpStatus.CONFLICT);
        }
    }

    private static void validateCreateRequest(AssetPipelineCreateRequest request) {
        if (request == null || trimToNull(request.name()) == null || request.name().trim().length() > 100) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", "작업 이름은 1자 이상 100자 이하여야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.slug() == null || !SLUG_PATTERN.matcher(request.slug().trim()).matches()) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID",
                    "slug는 소문자·숫자·하이픈으로 3자 이상 입력하세요.", HttpStatus.BAD_REQUEST);
        }
        if (request.kind() == null) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", "에셋 종류가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        String outputKey = trimToNull(request.outputAssetKey());
        if (outputKey == null || outputKey.length() > 255 || outputKey.contains("..")
                || !ASSET_KEY_PATTERN.matcher(outputKey).matches()) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", "유효한 outputAssetKey가 필요합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        String themeCode = trimToNull(request.themeCode());
        if (themeCode != null && themeCode.length() > 100) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", "themeCode는 100자 이하여야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        validateOptionalKey(request.sourceAssetKey(), "sourceAssetKey");
        validateOptionalKey(request.previewAssetKey(), "previewAssetKey");
        validateOptionalKey(request.expectedOldAssetKey(), "expectedOldAssetKey");
        if (request.targetItemId() != null && request.targetItemId() <= 0) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", "targetItemId는 양수여야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        if ((request.targetItemId() == null) != (trimToNull(request.expectedOldAssetKey()) == null)) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID",
                    "기존 아이템 교체는 targetItemId와 expectedOldAssetKey를 함께 입력해야 합니다.",
                    HttpStatus.BAD_REQUEST);
        }
        validateKindOutput(request.kind(), outputKey);
    }

    private static void validateKindOutput(AssetPipelineKind kind, String outputKey) {
        boolean valid = switch (kind) {
            case STATIC_FURNITURE -> outputKey.startsWith("items/")
                    && !AssetKeyClassifier.isAnimated(outputKey);
            case ANIMATED_FURNITURE -> outputKey.startsWith("items/")
                    && AssetKeyClassifier.isAnimated(outputKey);
            case CHARACTER_ANIMATION -> CHARACTER_ANIMATION_PATTERN.matcher(outputKey).matches();
            case HOUSE_FRAME -> HOUSE_FRAME_PATTERN.matcher(outputKey).matches();
        };
        if (!valid) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID",
                    kind + " 명명 규칙과 outputAssetKey가 맞지 않습니다.", HttpStatus.BAD_REQUEST);
        }
    }

    private static void validateOptionalKey(String key, String field) {
        String normalized = trimToNull(key);
        if (normalized != null && (normalized.length() > 255 || normalized.contains("..")
                || !ASSET_KEY_PATTERN.matcher(normalized).matches())) {
            throw error("ASSET_PIPELINE_REQUEST_INVALID", field + " 형식이 올바르지 않습니다.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AssetFoundryException error(String code, String message, HttpStatus status) {
        return new AssetFoundryException(code, message, status);
    }
}
