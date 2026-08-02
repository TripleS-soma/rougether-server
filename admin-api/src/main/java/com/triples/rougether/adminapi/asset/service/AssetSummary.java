package com.triples.rougether.adminapi.asset.service;

import com.triples.rougether.adminapi.asset.AssetKeyClassifier;
import java.time.Instant;

// 저장소에 있는 에셋 한 건의 요약. key + base URL 조합은 화면 JS 가 한다.
public record AssetSummary(String key, long size, Instant lastModified, boolean animated) {

    public AssetSummary(String key, long size, Instant lastModified) {
        this(key, size, lastModified, AssetKeyClassifier.isAnimated(key));
    }
}
