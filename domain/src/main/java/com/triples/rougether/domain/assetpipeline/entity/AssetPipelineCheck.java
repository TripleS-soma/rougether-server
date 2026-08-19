package com.triples.rougether.domain.assetpipeline.entity;

import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "asset_pipeline_checks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_asset_pipeline_checks_job_code",
                columnNames = {"job_id", "check_code"}))
public class AssetPipelineCheck extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AssetPipelineJob job;

    @Column(name = "check_code", length = 60, nullable = false)
    private String code;

    @Column(name = "label", length = 100, nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20, nullable = false)
    private AssetPipelineCheckResult result;

    @Column(name = "score")
    private Integer score;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "message", length = 500)
    private String message;

    public AssetPipelineCheck(AssetPipelineJob job,
                              String code,
                              String label,
                              AssetPipelineCheckResult result,
                              Integer score,
                              boolean required,
                              String message) {
        this.job = job;
        this.code = code;
        this.label = label;
        this.result = result;
        this.score = score;
        this.required = required;
        this.message = message;
    }

    void replaceWith(AssetPipelineCheck source) {
        this.label = source.label;
        this.result = source.result;
        this.score = source.score;
        this.required = source.required;
        this.message = source.message;
    }
}
