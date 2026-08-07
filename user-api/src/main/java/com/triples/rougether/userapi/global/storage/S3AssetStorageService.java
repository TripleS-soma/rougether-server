package com.triples.rougether.userapi.global.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// 이미지를 S3 에 올리고 object key 를 발급한다. key 규칙: {kind}/{uuid}.{ext}
// 전체 URL 이 아니라 key 만 저장/반환하고, CDN base URL 조합은 클라이언트가 한다(spec 원칙).
@Service
public class S3AssetStorageService implements AssetStorageService {

    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final S3Client s3Client;
    private final AssetProperties properties;

    public S3AssetStorageService(S3Client s3Client, AssetProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public String upload(byte[] content, String contentType, String kind) {
        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식: " + contentType);
        }

        String key = kind + "/" + UUID.randomUUID() + "." + extension;
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.s3().bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public StoredAsset read(String key) {
        var response = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.s3().bucket())
                .key(key)
                .build());
        return new StoredAsset(response.asByteArray(), response.response().contentType());
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        if (!properties.s3().purgeVersionsOnDelete()) {
            deleteObject(key, null);
            return;
        }

        // 버전 관리 bucket의 DeleteObject는 삭제 marker만 만들므로 개인정보 원본 파기가 아니다.
        // 정확히 같은 key의 object version과 기존 delete marker를 모두 수집한 뒤 versionId로 삭제한다.
        List<String> versionIds = listVersionIds(key);
        for (String versionId : versionIds) {
            deleteObject(key, versionId);
        }
    }

    private List<String> listVersionIds(String key) {
        List<String> versionIds = new ArrayList<>();
        String keyMarker = null;
        String versionIdMarker = null;

        do {
            ListObjectVersionsRequest.Builder request = ListObjectVersionsRequest.builder()
                    .bucket(properties.s3().bucket())
                    .prefix(key);
            if (keyMarker != null) {
                request.keyMarker(keyMarker);
            }
            if (versionIdMarker != null) {
                request.versionIdMarker(versionIdMarker);
            }

            ListObjectVersionsResponse response = s3Client.listObjectVersions(request.build());
            response.versions().stream()
                    .filter(version -> key.equals(version.key()))
                    .map(version -> version.versionId())
                    .forEach(versionIds::add);
            response.deleteMarkers().stream()
                    .filter(marker -> key.equals(marker.key()))
                    .map(marker -> marker.versionId())
                    .forEach(versionIds::add);

            if (!Boolean.TRUE.equals(response.isTruncated())) {
                break;
            }
            keyMarker = response.nextKeyMarker();
            versionIdMarker = response.nextVersionIdMarker();
        } while (true);

        return versionIds;
    }

    private void deleteObject(String key, String versionId) {
        DeleteObjectRequest.Builder request = DeleteObjectRequest.builder()
                .bucket(properties.s3().bucket())
                .key(key);
        if (versionId != null) {
            request.versionId(versionId);
        }
        // S3 DeleteObject는 미존재 key/version도 성공 응답이라 멱등함.
        s3Client.deleteObject(
                request.build());
    }
}
