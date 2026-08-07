package com.triples.rougether.userapi.global.storage;

// 사용자 업로드 저장소 추상화. 구현은 S3(S3AssetStorageService), 반환값은 발급된 object key.
public interface AssetStorageService {

    String upload(byte[] content, String contentType, String kind);

    // 공개 CDN에서 제외한 사용자 소유 이미지를 인증된 API에서 읽는다.
    StoredAsset read(String key);

    // 회원탈퇴 등 원본 파기용 삭제. null/blank key 는 no-op.
    void delete(String key);
}
