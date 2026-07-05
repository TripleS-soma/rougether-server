package com.triples.rougether.adminapi.asset.service;

import java.util.List;

// 에셋 저장소 추상화. 구현은 S3(S3AssetStorageService), 반환값은 발급된 object key.
public interface AssetStorageService {

    String upload(byte[] content, String contentType, String kind);

    // kind prefix 아래의 에셋 전체 목록 (어드민 조회 화면용).
    List<AssetSummary> list(String kind);
}
