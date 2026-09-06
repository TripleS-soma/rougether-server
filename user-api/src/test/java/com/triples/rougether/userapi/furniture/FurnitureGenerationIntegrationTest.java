package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.*;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.repository.*;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient.*;
import com.triples.rougether.userapi.furniture.dto.*;
import com.triples.rougether.userapi.furniture.service.*;
import com.triples.rougether.userapi.global.storage.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {"furniture.generation.enabled=true", "furniture.generation.worker-enabled=false",
        "furniture.generation.style-reference-keys=items/ref.png"})
class FurnitureGenerationIntegrationTest {
    @Autowired FurnitureGenerationService service;
    @Autowired FurnitureGenerationTransactions transactions;
    @Autowired FurnitureGenerationWorker worker;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired FurnitureGenerationFeedbackRepository feedbacks;
    @Autowired UserRepository users;
    @MockitoSpyBean UserItemRepository inventory;
    @Autowired ItemRepository items;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean FurnitureAiClient ai;
    @MockitoBean AssetStorageService storage;
    @MockitoBean Clock kstClock;

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private User user;
    private byte[] valid;
    private Instant now;

    @BeforeEach void setup() {
        feedbacks.deleteAll();
        jobs.deleteAll();
        now = Instant.parse("2026-09-06T03:00:00Z");
        when(kstClock.instant()).thenAnswer(i -> now);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        user = users.save(User.signUp("photo-" + UUID.randomUUID() + "@example.test"));
        valid = FurnitureFixtures.png(true, false);
        objects.clear();
        objects.put("items/ref.png", valid);
        when(ai.available()).thenReturn(true);
        when(ai.generate(any(), any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new Generated(valid, 100, 30);
        });
        when(ai.review(any(), any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return review(Decision.ACCEPT);
        });
        when(storage.upload(any(), eq("image/png"), anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String key = i.getArgument(2) + "/" + UUID.randomUUID() + ".png";
            objects.put(key, i.getArgument(0));
            return key;
        });
        when(storage.read(anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            byte[] bytes = objects.get(i.getArgument(0));
            if (bytes == null) throw new IllegalStateException("missing test object");
            return new StoredAsset(bytes, "image/png");
        });
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
    }

    @Test void 사진_접수_생성_검수_개인_보관함_지급까지_연결() {
        var job = submit();
        assertThat(job.status()).isEqualTo(Status.QUEUED);
        assertThat(job.assetKey()).isNull();
        worker.runNext();
        assertThat(service.get(user.getId(), job.id()).action()).isEqualTo(Action.REVIEW);
        worker.runNext();
        var result = service.get(user.getId(), job.id());
        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.assetKey()).startsWith("items/photo-furniture/furniture/");
        assertThat(result.userItemId()).isNotNull();
        assertThat(inventory.findInventoryByUserId(user.getId(), "furniture")).hasSize(1);
        assertThat(items.findActiveWithTheme()).noneMatch(i -> i.getAssetKey().equals(result.assetKey()));
        assertThat(result.imageAttempts()).isEqualTo(1);
        assertThat(jobs.findById(job.id()).orElseThrow().getInputTokens()).isEqualTo(200);
    }

    @Test void Astra가_부분수정과_전체재생성을_선택하고_수정사유를_다음호출에_전달() {
        when(ai.review(any(), any())).thenReturn(review(Decision.EDIT), review(Decision.REGENERATE), review(Decision.ACCEPT));
        var job = submit();
        for (int i = 0; i < 6; i++) worker.runNext();
        verify(ai).generate(any(), eq(Action.GENERATE));
        verify(ai).generate(argThat(c -> c.correction().equals("의자의 다리를 바로잡아 주세요")), eq(Action.EDIT));
        verify(ai).generate(any(), eq(Action.REGENERATE));
        assertThat(service.get(user.getId(), job.id()).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).hasSize(1);
    }

    @Test void 반복_품질실패는_세번에서_멈추고_지급하지_않음() {
        when(ai.review(any(), any())).thenReturn(review(Decision.REGENERATE));
        var job = submit();
        for (int i = 0; i < 10; i++) worker.runNext();
        assertThat(service.get(user.getId(), job.id()).failureCode()).isEqualTo("GENERATION_BUDGET_EXHAUSTED");
        verify(ai, times(3)).generate(any(), any());
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).isEmpty();
    }

    @Test void AI가_통과시켜도_투명도와_잘림_검사_실패는_지급_금지() {
        when(ai.generate(any(), any())).thenReturn(new Generated(FurnitureFixtures.png(false, true), 100, 30));
        var job = submit();
        worker.runNext();
        worker.runNext();
        assertThat(service.get(user.getId(), job.id()).failureCode()).isEqualTo("HARD_QA_REJECTED");
        verify(ai).review(any(), argThat(f -> f.contains("OBJECT_TOUCHES_CANVAS_BORDER")));
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).isEmpty();
    }

    @Test void 사용자_피드백은_즉시_생성하지_않고_재검토하며_수정본은_같은_보관함_ID에_연결() {
        var done = complete();
        UUID requestId = UUID.randomUUID();
        var feedback = new FurnitureFeedbackRequest(requestId, "다리가 이상해요");
        service.feedback(user.getId(), done.id(), feedback);
        service.feedback(user.getId(), done.id(), feedback);
        assertThat(feedbacks.count()).isEqualTo(1);
        assertThat(service.get(user.getId(), done.id()).action()).isEqualTo(Action.REVIEW);
        when(ai.review(any(), any())).thenReturn(review(Decision.EDIT), review(Decision.ACCEPT));
        worker.runNext();
        verify(ai, times(1)).generate(any(), any());
        worker.runNext();
        worker.runNext();
        var corrected = service.get(user.getId(), done.id());
        assertThat(corrected.userItemId()).isEqualTo(done.userItemId());
        assertThat(corrected.assetKey()).isNotEqualTo(done.assetKey());
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).singleElement()
                .satisfies(i -> assertThat(i.getItem().getAssetKey()).isEqualTo(corrected.assetKey()));
    }

    @Test void 피드백_검수에서_유지가_선택되면_추가_이미지_호출_없음() {
        var done = complete();
        service.feedback(user.getId(), done.id(), new FurnitureFeedbackRequest(UUID.randomUUID(), "다시 봐주세요"));
        worker.runNext();
        verify(ai, times(1)).generate(any(), any());
        assertThat(service.get(user.getId(), done.id()).assetKey()).isEqualTo(done.assetKey());
    }

    @Test void 같은_요청은_동시에_접수해도_한번만_저장하고_다른_사진은_충돌() throws Exception {
        UUID requestId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<FurnitureGenerationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                start.await();
                return service.submit(user.getId(), requestId, "앞 의자", photo());
            }));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
        }
        verify(storage, times(1)).upload(any(), any(), contains("/source"));
        assertCode(() -> service.submit(user.getId(), requestId, "뒤 의자", photo()), "FURNITURE_REQUEST_CONFLICT");
    }

    @Test void 다른_사용자의_조회와_피드백을_차단하고_소유자_할당은_JWT_기준() {
        var done = complete();
        User other = users.save(User.signUp());
        assertCode(() -> service.get(other.getId(), done.id()), "FURNITURE_JOB_NOT_FOUND");
        assertCode(() -> service.feedback(other.getId(), done.id(),
                new FurnitureFeedbackRequest(UUID.randomUUID(), "바꿔줘")), "FURNITURE_JOB_NOT_FOUND");
    }

    @Test void 만료된_lease의_늦은_응답은_지급할_수_없고_외부_호출을_자동_재전송하지_않음() {
        var job = submit();
        var claim = transactions.claim(job.id());
        assertThat(transactions.claim(job.id())).isNull();
        now = now.plusSeconds(241);
        worker.maintain();
        assertThat(transactions.generated(claim, "private/late.png", 100, 30)).isFalse();
        assertThat(service.get(user.getId(), job.id()).failureCode()).isEqualTo("WORKER_INTERRUPTED");
        worker.runNext();
        verify(ai, never()).generate(any(), any());
    }

    @Test void 작업자_동시_선점은_하나만_성공() throws Exception {
        var job = submit();
        AtomicInteger claimed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                start.await();
                if (transactions.claim(job.id()) != null) claimed.incrementAndGet();
                return null;
            }));
            start.countDown();
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
        assertThat(claimed).hasValue(1);
        assertThat(jobs.findById(job.id()).orElseThrow().getImageAttempts()).isEqualTo(1);
    }

    @Test void 원본은_24시간_후_삭제하고_완료_가구는_유지() {
        var done = complete();
        var job = jobs.findById(done.id()).orElseThrow();
        String source = job.getSourceKey();
        String candidate = job.getCandidateKey();
        now = now.plus(Duration.ofHours(24));
        assertCode(() -> service.feedback(user.getId(), done.id(),
                new FurnitureFeedbackRequest(UUID.randomUUID(), "수정")), "FURNITURE_SOURCE_EXPIRED");
        worker.maintain();
        assertThat(objects).doesNotContainKeys(source, candidate).containsKey(done.assetKey());
        assertThat(jobs.findById(done.id()).orElseThrow().getSourceKey()).isNull();
    }

    @Test void 처리중_탈퇴하면_늦은_검수결과를_지급하지_않음() {
        var job = submit();
        worker.runNext();
        var claim = transactions.claim(job.id());
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            User owner = users.findByIdForUpdate(user.getId()).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(owner, "deletedAt", now);
        });
        assertThat(transactions.reviewed(claim, review(Decision.ACCEPT), true, "items/not-granted.png")).isFalse();
        worker.maintain();
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).isEmpty();
        assertThat(jobs.findById(job.id()).orElseThrow().getSourceKey()).isNull();
    }

    @Test void 일일_한도와_사용자별_동시작업_한도() {
        var first = submit();
        assertCode(this::submit, "FURNITURE_JOB_IN_PROGRESS");
        worker.runNext(); worker.runNext();
        complete();
        assertCode(this::submit, "FURNITURE_DAILY_LIMIT");
    }

    @Test void 공급자_장애에는_가짜_가구나_무한_재시도가_없음() {
        when(ai.generate(any(), any())).thenThrow(new FurnitureAiFailure("PROVIDER_AUTH_FAILED"));
        var job = submit();
        worker.runNext(); worker.runNext();
        assertThat(service.get(user.getId(), job.id()).failureCode()).isEqualTo("PROVIDER_AUTH_FAILED");
        verify(ai, times(1)).generate(any(), any());
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).isEmpty();
        when(ai.available()).thenReturn(false);
        assertCode(this::submit, "FURNITURE_GENERATION_UNAVAILABLE");
    }

    @Test void 보관함_저장이_실패하면_마스터_아이템과_성공상태도_함께_롤백() {
        var job = submit();
        worker.runNext();
        long itemCount = items.count();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("test inventory failure"))
                .when(inventory).save(any(com.triples.rougether.domain.shop.entity.UserItem.class));
        worker.runNext();
        assertThat(items.count()).isEqualTo(itemCount);
        assertThat(service.get(user.getId(), job.id()).status()).isEqualTo(Status.FAILED);
        assertThat(service.get(user.getId(), job.id()).userItemId()).isNull();
        assertThat(inventory.findInventoryByUserId(user.getId(), null)).isEmpty();
        assertThat(objects.keySet()).noneMatch(k -> k.startsWith("items/photo-furniture/"));
    }

    @Test void 외부에서_수정한_아이템은_유지_판정이어도_옛_결과로_덮어쓰지_않음() {
        var done = complete();
        Long itemId = inventory.findInventoryByUserId(user.getId(), null).getFirst().getItem().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                items.replaceAssetKeyIfUnchanged(itemId, done.assetKey(), "items/admin-corrected.png"));
        service.feedback(user.getId(), done.id(), new FurnitureFeedbackRequest(UUID.randomUUID(), "검수해줘"));
        worker.runNext();
        assertThat(service.get(user.getId(), done.id()).failureCode()).isEqualTo("RESULT_CHANGED_EXTERNALLY");
        assertThat(items.findById(itemId).orElseThrow().getAssetKey()).isEqualTo("items/admin-corrected.png");
    }

    private Review review(Decision decision) {
        return new Review(decision, "파란 사무용 의자", "다리 형태를 확인했습니다", "의자의 다리를 바로잡아 주세요", 100, 20);
    }
    private MockMultipartFile photo() { return new MockMultipartFile("photo", "chair.png", "image/png", valid); }
    private FurnitureGenerationResponse submit() { return service.submit(user.getId(), UUID.randomUUID(), "앞 의자", photo()); }
    private FurnitureGenerationResponse complete() {
        var job = submit(); worker.runNext(); worker.runNext(); return service.get(user.getId(), job.id());
    }
    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }
}
