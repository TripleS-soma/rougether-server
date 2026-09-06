package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.ai.OpenAiFurnitureClient;
import com.triples.rougether.userapi.furniture.service.*;
import com.triples.rougether.userapi.global.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

// 명시적 opt-in에서만 유료 OpenAI 호출함. DB는 테스트 MySQL, S3는 메모리 저장소이므로 운영 데이터는 변경하지 않음.
@EnabledIfEnvironmentVariable(named = "FURNITURE_LIVE_SMOKE", matches = "true")
@SpringBootTest(properties = {"furniture.generation.enabled=true", "furniture.generation.worker-enabled=false",
        "furniture.generation.timeout=180s"})
class FurnitureGenerationLiveSmokeTest {
    @Autowired FurnitureGenerationService service;
    @Autowired FurnitureGenerationWorker worker;
    @Autowired FurnitureGenerationProperties properties;
    @Autowired UserRepository users;
    @MockitoBean AssetStorageService storage;
    @MockitoSpyBean OpenAiFurnitureClient ai;

    @Test void 실제_사진을_Astra로_생성_검수하고_보관함에_연결() throws Exception {
        Path output = Path.of(System.getenv("FURNITURE_LIVE_OUTPUT"));
        Files.createDirectories(output);
        byte[] photo = Files.readAllBytes(Path.of(System.getenv("FURNITURE_LIVE_PHOTO")));
        byte[] reference = Files.readAllBytes(Path.of(System.getenv("FURNITURE_LIVE_REFERENCE")));
        String recordedPath = System.getenv("FURNITURE_LIVE_CANDIDATE");
        if (recordedPath != null && !recordedPath.isBlank()) {
            byte[] recorded = Files.readAllBytes(Path.of(recordedPath));
            var replayCount = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(i -> {
                if (replayCount.incrementAndGet() > 1) throw new FurnitureAiFailure("LIVE_GENERATION_DISABLED");
                return new FurnitureAiClient.Generated(recorded, 0, 0);
            }).when(ai).generate(any(), any());
        }
        Files.writeString(output.resolve("mode.txt"), recordedPath == null
                ? "live-generation-and-review" : "recorded-generation-and-live-review; no new image generation");
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        for (String key : properties.styleReferenceKeys()) objects.put(key, reference);
        when(storage.upload(any(), eq("image/png"), anyString())).thenAnswer(i -> {
            String key = i.getArgument(2) + "/" + UUID.randomUUID() + ".png";
            byte[] bytes = i.getArgument(0);
            objects.put(key, bytes);
            if (key.contains("/candidate/")) Files.write(output.resolve("candidate-" + UUID.randomUUID() + ".png"), bytes);
            return key;
        });
        when(storage.read(anyString())).thenAnswer(i -> new StoredAsset(objects.get(i.getArgument(0)), "image/png"));
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
        User user = users.save(User.signUp("furniture-live-smoke@example.test"));
        var result = service.submit(user.getId(), UUID.randomUUID(), "사진 맨 앞의 파란 좌석 사무용 의자",
                new MockMultipartFile("photo", "chair.jpg", "image/jpeg", photo));
        for (int i = 0; i < properties.maxImageAttempts() + properties.maxReviewAttempts(); i++) {
            if (result.status() == Status.SUCCEEDED || result.status() == Status.FAILED) break;
            worker.runNext();
            result = service.get(user.getId(), result.id());
            Files.writeString(output.resolve("result.json"), JsonMapper.builder().build().writeValueAsString(result));
            Files.writeString(output.resolve("stages.jsonl"), JsonMapper.builder().build().writeValueAsString(result) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        if (result.assetKey() != null) {
            byte[] sprite = objects.get(result.assetKey());
            Files.write(output.resolve("furniture.png"), sprite);
            var images = new FurnitureImages();
            Files.write(output.resolve("furniture-on-light.png"), images.preview(sprite, 0xFAF7F1));
            Files.write(output.resolve("furniture-on-dark.png"), images.preview(sprite, 0x30343B));
        }
        assertThat(result.status()).as("failureCode=%s, decision=%s", result.failureCode(), result.decision()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.userItemId()).isNotNull();
    }
}
