package com.triples.rougether.adminapi.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.triples.rougether.adminapi.asset.config.AssetProperties;
import com.triples.rougether.adminapi.asset.service.AssetAlreadyExistsException;
import com.triples.rougether.adminapi.asset.service.S3AssetStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class S3AssetStorageServiceTest {

    @Mock
    S3Client s3Client;

    S3AssetStorageService storage;

    @BeforeEach
    void setUp() {
        AssetProperties properties = new AssetProperties(
                new AssetProperties.S3("test-bucket", "ap-northeast-2"),
                "https://cdn.example.com");
        storage = new S3AssetStorageService(s3Client, properties);
    }

    @Test
    void 요청한_key로_기존_객체를_덮어쓰지_않고_업로드한다() {
        given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willReturn(PutObjectResponse.builder().build());

        String key = storage.upload(
                new byte[]{1, 2, 3}, "image/png", "items", "items/gacha/forest-box.png");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(key).isEqualTo("items/gacha/forest-box.png");
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo(key);
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.ifNoneMatch()).isEqualTo("*");
    }

    @Test
    void key가_없으면_기존_UUID_규칙으로_자동_발급한다() {
        given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willReturn(PutObjectResponse.builder().build());

        String key = storage.upload(new byte[]{1}, "image/webp", "characters", null);

        assertThat(key).matches("characters/[0-9a-f-]{36}\\.webp");
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 412})
    void S3가_동일_key의_조건부_업로드를_거부하면_중복_예외를_던진다(int statusCode) {
        given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willThrow(S3Exception.builder().statusCode(statusCode).message("Conditional write failed").build());

        assertThatThrownBy(() -> storage.upload(
                new byte[]{1}, "image/png", "items", "items/existing.png"))
                .isInstanceOf(AssetAlreadyExistsException.class)
                .hasMessageContaining("items/existing.png");
    }
}
