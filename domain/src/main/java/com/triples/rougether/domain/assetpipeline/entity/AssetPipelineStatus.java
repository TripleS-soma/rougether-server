package com.triples.rougether.domain.assetpipeline.entity;

import java.util.List;

public enum AssetPipelineStatus {
    DRAFT,
    BUILT,
    VALIDATED,
    REVIEW_CANDIDATE,
    APPROVED,
    UPLOADED,
    LINKED_DEV,
    SEED_SYNCED,
    CLIENT_VERIFIED;

    private static final List<AssetPipelineStatus> ORDERED = List.of(values());

    public AssetPipelineStatus next() {
        int nextIndex = ordinal() + 1;
        return nextIndex < ORDERED.size() ? ORDERED.get(nextIndex) : null;
    }

    public boolean canMoveTo(AssetPipelineStatus target) {
        return target == next() || (this == REVIEW_CANDIDATE && target == DRAFT);
    }
}
