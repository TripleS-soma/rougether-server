package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.ai.OpenAiFurnitureClient;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.service.FurnitureGenerationWorker;
import com.triples.rougether.furniture.service.FurnitureImages;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.infra.assets.AssetStorageService;
import com.triples.rougether.infra.assets.StoredAsset;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.invocation.InvocationOnMock;
import tools.jackson.databind.json.JsonMapper;

// 실제 유료 API의 소량 병렬 smoke. 메모리 용량 시험과 달리 테스트 JVM 힙은 1GiB다.
@EnabledIfEnvironmentVariable(named = "FURNITURE_FLARE_CONCURRENT_LIVE", matches = "true")
@SpringBootTest(properties = {"furniture.generation.enabled=true", "furniture.generation.timeout=180s"})
class FurnitureFlareConcurrentLiveTest {
    @Autowired FurnitureGenerationService service;
    @Autowired FurnitureGenerationWorker worker;
    @Autowired FurnitureGenerationProperties properties;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AssetStorageService storage;
    @MockitoSpyBean OpenAiFurnitureClient ai;
    final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    final Queue<Map<String, Object>> calls = new ConcurrentLinkedQueue<>();
    final JsonMapper json = JsonMapper.builder().build();

    Object observe(String stage, InvocationOnMock invocation) throws Throwable {
        long start = System.nanoTime();
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        var record = new LinkedHashMap<String, Object>();
        record.put("stage", stage);
        try {
            Object value = invocation.callRealMethod();
            if (value instanceof FurnitureAiClient.Generated generated) record.put("imageBytes", generated.image().length);
            if (value instanceof FurnitureAiClient.Review review) record.put("decision", review.decision().name());
            record.put("success", true);
            return value;
        } catch (Throwable failure) {
            record.put("success", false);
            record.put("errorType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            record.put("seconds", (System.nanoTime() - start) / 1_000_000_000.0);
            calls.add(record); active.decrementAndGet();
        }
    }

    @Test void 실사진_4건을_동시에_생성하고_검수한다() throws Exception {
        Path samples = Path.of(System.getenv("FURNITURE_LIVE_OUTPUT"));
        Path output = samples.resolve("concurrent-4");
        Files.createDirectories(output);
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        byte[] reference = Files.readAllBytes(samples.resolve("reference.png"));
        for (String key : properties.styleReferenceKeys()) objects.put(key, reference);
        when(storage.upload(any(), eq("image/png"), anyString())).thenAnswer(i -> {
            String key = i.getArgument(2) + "/" + UUID.randomUUID() + ".png";
            objects.put(key, i.getArgument(0));
            return key;
        });
        when(storage.read(anyString())).thenAnswer(i -> new StoredAsset(objects.get(i.getArgument(0)), "image/png"));
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
        doAnswer(i -> observe("EXTRACT", i)).when(ai).extract(any(), anyString());
        doAnswer(i -> observe("GENERATE", i)).when(ai).generate(any(), any());
        doAnswer(i -> observe("REVIEW", i)).when(ai).review(any(), any());
        jdbc.update("update furniture_worker_capacity set max_in_flight=4, execution_enabled=true where id=1");
        Map<String, Long> jobs = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (int n = 0; n < 4; n++) {
            String slug = n % 2 == 0 ? "mac-mini" : "bear-cake";
            User user = users.save(User.signUp("flare-concurrent-" + n + "@example.test"));
            String hint = slug.equals("mac-mini") ? "사진의 은색 맥 미니 본체 하나" : "사진의 산타 모자를 쓴 흰 곰 얼굴 케이크 하나";
            var job = service.submit(user.getId(), UUID.randomUUID(), hint,
                    new MockMultipartFile("photo", "upload.jpg", "image/jpeg", Files.readAllBytes(samples.resolve(slug).resolve("upload.jpg"))));
            jobs.put(job.id(), user.getId()); labels.put(job.id(), slug + "-" + n);
        }
        var stop = new AtomicBoolean();
        var executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        Map<String, FurnitureGenerationResponse> completed = new LinkedHashMap<>();
        Map<String, Double> elapsed = new LinkedHashMap<>();
        long started = System.nanoTime();
        try {
            for (int n = 0; n < 4; n++) futures.add(executor.submit(() -> {
                while (!stop.get()) {
                    worker.runNext();
                    try { Thread.sleep(50); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }));
            while (completed.size() < 4 && System.nanoTime() - started < TimeUnit.MINUTES.toNanos(8)) {
                for (var entry : jobs.entrySet()) {
                    if (completed.containsKey(entry.getKey())) continue;
                    var result = service.get(entry.getValue(), entry.getKey());
                    if (result.status() == Status.SUCCEEDED || result.status() == Status.FAILED) {
                        String label = labels.get(result.id());
                        completed.put(result.id(), result);
                        elapsed.put(label, (System.nanoTime() - started) / 1_000_000_000.0);
                        Files.writeString(output.resolve(label + "-result.json"), json.writeValueAsString(result));
                        if (result.assetKey() != null) {
                            byte[] png = objects.get(result.assetKey());
                            Files.write(output.resolve(label + ".png"), png);
                            Files.write(output.resolve(label + "-on-light.png"), new FurnitureImages().preview(png, 0xFAF7F1));
                        }
                    }
                }
                Thread.sleep(250);
            }
        } finally {
            stop.set(true); executor.shutdown();
            if (!executor.awaitTermination(190, TimeUnit.SECONDS)) executor.shutdownNow();
            Files.writeString(output.resolve("calls.json"), json.writeValueAsString(calls));
            Files.writeString(output.resolve("summary.json"), json.writeValueAsString(Map.of(
                    "imageModel", properties.imageModel(), "peakConcurrentAiCalls", peak.get(),
                    "completed", completed.size(), "elapsedSeconds", elapsed,
                    "maxHeapBytes", Runtime.getRuntime().maxMemory())));
        }
        for (Future<?> future : futures) future.get(1, TimeUnit.SECONDS);
        assertThat(completed).hasSize(4);
        assertThat(completed.values()).allSatisfy(result -> {
            assertThat(result.status()).as("%s", result.failureCode()).isEqualTo(Status.SUCCEEDED);
            assertThat(result.userItemId()).isNotNull();
        });
        assertThat(peak.get()).isEqualTo(4);
    }
}
