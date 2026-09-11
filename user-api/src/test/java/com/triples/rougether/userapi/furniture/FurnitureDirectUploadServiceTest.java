package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.infra.assets.AssetProperties;
import com.triples.rougether.userapi.furniture.service.FurnitureDirectUploadService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class FurnitureDirectUploadServiceTest {
    @Test void 실제_서명기는_원본키_체크섬_크기_덮어쓰기금지를_서명하며_네트워크를_호출하지_않음() {
        var now = Instant.now();
        var tx = mock(FurnitureGenerationTransactions.class);
        UUID request = UUID.randomUUID();
        var job = FurnitureGenerationJob.create(7L, request.toString(), "digest", "", now, now.plusSeconds(86400));
        String key = "private/furniture-generation/raw/" + job.getId();
        when(tx.reserveDirect(eq(7L), anyString(), anyString(), anyLong(), anyString(), anyString())).thenReturn(
                new FurnitureGenerationTransactions.UploadReservation(FurnitureGenerationResponse.from(job), key, "a".repeat(64), 123, "image/jpeg", now.plusSeconds(900)));
        var config = new FurnitureGenerationProperties(true, "test", "test", Duration.ofSeconds(30), 3, 6, Duration.ofHours(24), List.of("items/ref.png"));
        try (var signer = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-only", "test-only"))).build()) {
            var service = new FurnitureDirectUploadService(tx, signer, new AssetProperties(new AssetProperties.S3("test-bucket", "ap-northeast-2", false)), config, Clock.systemUTC());
            var response = service.start(7L, request, "a".repeat(64), 123, "image/jpeg", "  힌트  ");
            assertThat(response.uploadUrl()).contains(key).contains("X-Amz-Expires=300");
            assertThat(response.headers()).containsEntry("if-none-match", List.of("*"));
            assertThat(response.headers()).containsEntry("content-length", List.of("123"));
            assertThat(response.headers()).containsEntry("x-amz-checksum-sha256", List.of(Base64.getEncoder().encodeToString(HexFormat.of().parseHex("a".repeat(64)))));
            assertThat(response.headers()).doesNotContainKey("host");
            verify(tx).reserveDirect(7L, request.toString(), "a".repeat(64), 123, "image/jpeg", "힌트");
        }
    }
}
