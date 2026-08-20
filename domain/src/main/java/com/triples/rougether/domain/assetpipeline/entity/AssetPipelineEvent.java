package com.triples.rougether.domain.assetpipeline.entity;

import com.triples.rougether.domain.support.BaseCreatedEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "asset_pipeline_events")
public class AssetPipelineEvent extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AssetPipelineJob job;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private AssetPipelineStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30, nullable = false)
    private AssetPipelineStatus toStatus;

    @Column(name = "actor", length = 50, nullable = false)
    private String actor;

    @Column(name = "note", length = 500, nullable = false)
    private String note;

    public AssetPipelineEvent(AssetPipelineJob job,
                              AssetPipelineStatus fromStatus,
                              AssetPipelineStatus toStatus,
                              String actor,
                              String note) {
        this.job = job;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.note = note;
    }
}
