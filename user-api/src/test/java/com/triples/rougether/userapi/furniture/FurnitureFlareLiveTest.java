package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.ai.OpenAiFurnitureClient;
import com.triples.rougether.userapi.furniture.service.*;
import com.triples.rougether.furniture.service.*;
import com.triples.rougether.infra.assets.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class FurnitureFlareLiveTest {
    @Autowired FurnitureGenerationService service;
    @Autowired FurnitureGenerationWorker worker;
    @Autowired FurnitureGenerationProperties properties;
    @Autowired UserRepository users;
    @MockitoBean AssetStorageService storage;
    @MockitoSpyBean OpenAiFurnitureClient ai;

    @ParameterizedTest
    @ValueSource(strings = {"mac-mini", "bear-cake"})
    void 실제_사진을_Flare로_생성_검수(String slug) throws Exception {
        Path output = Path.of(System.getenv("FURNITURE_LIVE_OUTPUT")).resolve(slug);
        Files.createDirectories(output);
        doAnswer(i -> {
            var extracted = (FurnitureAiClient.Extracted) i.callRealMethod();
            Files.writeString(output.resolve("subject.json"), extracted.subjectJson());
            return extracted;
        }).when(ai).extract(any(), anyString());
        byte[] original = Files.readAllBytes(output.resolve("source.png"));
        // 클립보드 PNG가 업로드 10MiB 한도를 넘을 수 있어 사진을 JPEG로 인코딩한다. 크롭/리사이즈는 하지 않는다.
        var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
        var rgb = new java.awt.image.BufferedImage(decoded.getWidth(), decoded.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        graphics.drawImage(decoded, 0, 0, null); graphics.dispose();
        var encoded = new java.io.ByteArrayOutputStream();
        var writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(encoded)) {
            writer.setOutput(stream); var params = writer.getDefaultWriteParam();
            params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(0.92f);
            writer.write(null, new javax.imageio.IIOImage(rgb, null, null), params);
        } finally { writer.dispose(); }
        byte[] photo = encoded.toByteArray();
        Files.write(output.resolve("upload.jpg"), photo);
        Files.writeString(output.resolve("configuration.json"), JsonMapper.builder().build().writeValueAsString(Map.of(
                "imageModel", properties.imageModel(), "orchestrator", properties.model(),
                "originalBytes", original.length, "uploadBytes", photo.length,
                "width", rgb.getWidth(), "height", rgb.getHeight(), "jpegQuality", 0.92,
                "quality", "medium", "size", "1024x1024", "background", "transparent")));
        doAnswer(i -> {
            var review = (FurnitureAiClient.Review) i.callRealMethod();
            Files.writeString(output.resolve("reviews.jsonl"), JsonMapper.builder().build().writeValueAsString(review) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return review;
        }).when(ai).review(any(), any());
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
            if (key.contains("/candidate/")) {
                String name = "candidate-" + UUID.randomUUID();
                Files.write(output.resolve(name + ".png"), bytes);
                var images = new FurnitureImages();
                Files.write(output.resolve(name + "-on-light.png"), images.preview(bytes, 0xFAF7F1));
                Files.write(output.resolve(name + "-on-dark.png"), images.preview(bytes, 0x30343B));
            }
            return key;
        });
        when(storage.read(anyString())).thenAnswer(i -> new StoredAsset(objects.get(i.getArgument(0)), "image/png"));
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
        User user = users.save(User.signUp("furniture-flare-" + slug + "@example.test"));
        var result = service.submit(user.getId(), UUID.randomUUID(), slug.equals("mac-mini") ? "사진의 은색 맥 미니 본체 하나" : "사진의 산타 모자를 쓴 흰 곰 얼굴 케이크 하나",
                new MockMultipartFile("photo", "upload.jpg", "image/jpeg", photo));
        for (int i = 0; i < 1 + properties.maxImageAttempts() + properties.maxReviewAttempts(); i++) {
            if (result.status() == Status.SUCCEEDED || result.status() == Status.FAILED) break;
            long started = System.nanoTime();
            String stage = "step-" + i;
            worker.runNext();
            Files.writeString(output.resolve("timing.jsonl"), JsonMapper.builder().build().writeValueAsString(Map.of(
                    "stage", stage, "seconds", (System.nanoTime() - started) / 1_000_000_000.0)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
