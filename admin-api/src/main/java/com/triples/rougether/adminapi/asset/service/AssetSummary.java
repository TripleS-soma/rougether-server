package com.triples.rougether.adminapi.asset.service;

import java.time.Instant;

// 저장소에 있는 에셋 한 건의 요약. key + base URL 조합은 화면 JS 가 한다.
public record AssetSummary(String key, long size, Instant lastModified) {
}
