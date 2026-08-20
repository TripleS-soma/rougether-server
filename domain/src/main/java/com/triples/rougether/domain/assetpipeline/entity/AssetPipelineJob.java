package com.triples.rougether.domain.assetpipeline.entity;

import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "asset_pipeline_jobs")
public class AssetPipelineJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "slug", length = 100, nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_kind", length = 30, nullable = false)
    private AssetPipelineKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private AssetPipelineStatus status;

    @Column(name = "theme_code", length = 100)
    private String themeCode;

    @Column(name = "source_asset_key", length = 255)
    private String sourceAssetKey;

    @Column(name = "output_asset_key", length = 255, nullable = false)
    private String outputAssetKey;

    @Column(name = "preview_asset_key", length = 255)
    private String previewAssetKey;

    @Column(name = "target_item_id")
    private Long targetItemId;

    @Column(name = "expected_old_asset_key", length = 255)
    private String expectedOldAssetKey;

    @Column(name = "quality_score")
    private Integer qualityScore;

    @Column(name = "qa_passed", nullable = false)
    private boolean qaPassed;

    @Column(name = "created_by", length = 50, nullable = false)
    private String createdBy;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<AssetPipelineCheck> checks = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    private List<AssetPipelineEvent> events = new ArrayList<>();

    public AssetPipelineJob(String name,
                            String slug,
                            AssetPipelineKind kind,
                            String themeCode,
                            String sourceAssetKey,
                            String outputAssetKey,
                            String previewAssetKey,
                            Long targetItemId,
                            String expectedOldAssetKey,
                            String createdBy) {
        this.name = name;
        this.slug = slug;
        this.kind = kind;
        this.status = AssetPipelineStatus.DRAFT;
        this.themeCode = themeCode;
        this.sourceAssetKey = sourceAssetKey;
        this.outputAssetKey = outputAssetKey;
        this.previewAssetKey = previewAssetKey;
        this.targetItemId = targetItemId;
        this.expectedOldAssetKey = expectedOldAssetKey;
        this.createdBy = createdBy;
        this.qaPassed = false;
        this.events.add(new AssetPipelineEvent(this, null, this.status, createdBy, "작업 생성"));
    }

    public void replaceChecks(List<AssetPipelineCheck> nextChecks) {
        Map<String, AssetPipelineCheck> currentByCode = new HashMap<>();
        checks.forEach(check -> currentByCode.put(check.getCode(), check));
        Set<String> nextCodes = nextChecks.stream()
                .map(AssetPipelineCheck::getCode)
                .collect(Collectors.toSet());
        checks.removeIf(check -> !nextCodes.contains(check.getCode()));
        nextChecks.forEach(next -> {
            AssetPipelineCheck current = currentByCode.get(next.getCode());
            if (current == null) {
                checks.add(next);
            } else {
                current.replaceWith(next);
            }
        });
        List<Integer> scores = checks.stream()
                .map(AssetPipelineCheck::getScore)
                .filter(score -> score != null)
                .toList();
        qualityScore = scores.isEmpty()
                ? null
                : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        boolean hasRequiredCheck = checks.stream().anyMatch(AssetPipelineCheck::isRequired);
        qaPassed = hasRequiredCheck && checks.stream()
                .filter(AssetPipelineCheck::isRequired)
                .allMatch(check -> check.getResult() == AssetPipelineCheckResult.PASS);
    }

    public void moveTo(AssetPipelineStatus target, String actor, String note) {
        AssetPipelineStatus previous = status;
        status = target;
        if (target == AssetPipelineStatus.APPROVED) {
            approvedBy = actor;
        } else if (target == AssetPipelineStatus.DRAFT) {
            approvedBy = null;
        }
        events.add(new AssetPipelineEvent(this, previous, target, actor, note));
    }

    public void recordEvent(String actor, String note) {
        events.add(new AssetPipelineEvent(this, status, status, actor, note));
    }
}
