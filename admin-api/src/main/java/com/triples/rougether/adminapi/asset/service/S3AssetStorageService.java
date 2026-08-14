package com.triples.rougether.adminapi.asset.service;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

// 이미지를 S3 에 올리고 요청 key 또는 {kind}/{uuid}.{ext} 형식의 자동 key 를 반환함.
// 전체 URL 이 아니라 key 만 저장/반환하고, CDN base URL 조합은 클라이언트가 한다(spec 원칙).
@Service
public class S3AssetStorageService implements AssetStorageService {

    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
                    .withZone(ZoneOffset.UTC);

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
    public String upload(byte[] content, String contentType, String kind, String requestedKey) {
        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식: " + contentType);
        }

        String key = requestedKey == null
                ? kind + "/" + UUID.randomUUID() + "." + extension
                : requestedKey;
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.s3().bucket())
                            .key(key)
                            .contentType(contentType)
                            // Terraform-managed AWS S3만 지원하므로 조건부 쓰기로 동일 key 덮어쓰기를 원자적으로 막는다.
                            .ifNoneMatch("*")
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 409 || exception.statusCode() == 412) {
                throw new AssetAlreadyExistsException(key);
            }
            throw exception;
        }
        return key;
    }

    @Override
    public List<AssetSummary> list(String kind) {
        // listObjectsV2Paginator 가 continuation token 을 처리해 1000개 초과도 전부 순회한다.
        return s3Client.listObjectsV2Paginator(
                        ListObjectsV2Request.builder()
                                .bucket(properties.s3().bucket())
                                .prefix(kind + "/")
                                .build())
                .contents().stream()
                .map(object -> new AssetSummary(object.key(), object.size(), object.lastModified()))
                .toList();
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.s3().bucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw exception;
        }
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
    public AssetDeleteResult archiveAndDelete(String key) {
        String bucket = properties.s3().bucket();
        String archiveKey = "archive/admin-deleted/"
                + ARCHIVE_TIMESTAMP_FORMAT.format(Instant.now()) + "/" + key;
        s3Client.copyObject(CopyObjectRequest.builder()
                .bucket(bucket)
                .copySource(bucket + "/" + key)
                .key(archiveKey)
                .build());
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
        return new AssetDeleteResult(key, archiveKey);
    }
}
