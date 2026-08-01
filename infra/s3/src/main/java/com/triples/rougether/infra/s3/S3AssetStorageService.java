package com.triples.rougether.infra.s3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
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
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        // S3 DeleteObject 는 미존재 key 도 성공 응답이라 멱등함.
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(properties.s3().bucket())
                        .key(key)
                        .build());
    }

    @Override
    public List<StoredAsset> list(String kind) {
        // listObjectsV2Paginator 가 continuation token 을 처리해 1000개 초과도 전부 순회한다.
        return s3Client.listObjectsV2Paginator(
                        ListObjectsV2Request.builder()
                                .bucket(properties.s3().bucket())
                                .prefix(kind + "/")
                                .build())
                .contents().stream()
                .map(object -> new StoredAsset(object.key(), object.size(), object.lastModified()))
                .toList();
    }
}
