package com.triples.rougether.userapi.global.storage;

// 사용자 업로드 저장소 추상화. 구현은 S3(S3AssetStorageService), 반환값은 발급된 object key.
public interface AssetStorageService {

    String upload(byte[] content, String contentType, String kind);
}
