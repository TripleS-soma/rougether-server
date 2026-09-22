package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.infra.assets.AssetProperties;
import com.triples.rougether.infra.assets.AssetStorageService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@RequiredArgsConstructor
public class S3FeedImageStorage implements FeedImageStorage {
    private final S3Client s3;
    private final AssetProperties properties;
    private final AssetStorageService assets;

    public void put(String key, byte[] bytes) {
        validate(key);
        s3.putObject(PutObjectRequest.builder().bucket(properties.s3().bucket()).key(key)
                .contentType("image/jpeg").cacheControl("private, no-store").ifNoneMatch("*")
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))).build(), RequestBody.fromBytes(bytes));
    }
    public byte[] read(String key) {
        validate(key);
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.s3().bucket()).key(key)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))).build()).asByteArray();
    }
    public void delete(String key) { validate(key); assets.delete(key); }
    private void validate(String key) {
        if (key == null || !key.matches("private/feed/[a-f0-9-]{36}\\.jpg")) throw new IllegalArgumentException("피드 key 형식 오류");
    }
}
