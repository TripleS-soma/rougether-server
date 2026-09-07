package com.triples.rougether.userapi.billing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.*;
import com.triples.rougether.domain.billing.repository.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.billing.service.*;
import com.triples.rougether.userapi.billing.store.*;
import com.triples.rougether.userapi.billing.store.StorePurchaseVerifier.Verified;
import com.triples.rougether.userapi.furniture.FurnitureFixtures;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient.*;
import com.triples.rougether.userapi.furniture.service.*;
import com.triples.rougether.userapi.furniture.dto.FurnitureFeedbackRequest;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.global.storage.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {"billing.enabled=true", "billing.require-credits=true", "billing.environment=SANDBOX", "billing.worker-enabled=false",
        "billing.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "billing.apple.products.pack3=3", "billing.apple.products.pack7=7", "billing.google.products.pack3=3",
        "furniture.generation.enabled=true", "furniture.generation.worker-enabled=false",
        "furniture.generation.daily-limit=20", "furniture.generation.style-reference-keys=items/ref.png"})
@AutoConfigureMockMvc
class FurnitureBillingIntegrationTest {
    @Autowired FurnitureCreditTransactions credits;
    @Autowired FurnitureBillingService billing;
    @Autowired PurchaseReferenceCipher cipher;
    @Autowired FurnitureGenerationService furniture;
    @Autowired FurnitureGenerationWorker worker;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired FurnitureGenerationFeedbackRepository feedbacks;
    @Autowired FurnitureCreditAccountRepository accounts;
    @Autowired FurnitureCreditPurchaseRepository purchases;
    @Autowired FurnitureCreditReservationRepository reservations;
    @Autowired FurnitureCreditEntryRepository entries;
    @Autowired UserRepository users;
    @MockitoSpyBean UserItemRepository inventory;
    @MockitoBean ApplePurchaseVerifier apple;
    @MockitoBean GooglePurchaseVerifier google;
    @MockitoBean FurnitureAiClient ai;
    @MockitoBean AssetStorageService storage;
    @MockitoBean Clock kstClock;
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    private User user;
    private String accountToken;
    private Instant now;
    private byte[] sprite;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach void setup() {
        entries.deleteAll(); reservations.deleteAll(); purchases.deleteAll(); accounts.deleteAll();
        feedbacks.deleteAll(); jobs.deleteAll();
        user = users.save(User.signUp("billing-" + UUID.randomUUID() + "@example.test"));
        now = Instant.parse("2026-09-07T03:00:00Z");
        when(kstClock.instant()).thenAnswer(i -> now);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        accountToken = credits.balance(user.getId()).accountToken();
        when(apple.available()).thenReturn(true); when(google.available()).thenReturn(true);
        when(apple.verify(anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return verified(Store.APPLE, i.getArgument(0), "pack3", 1);
        });
        when(google.verify(anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return verified(Store.GOOGLE, i.getArgument(0), "pack3", 1);
        });
        when(ai.available()).thenReturn(true);
        sprite = FurnitureFixtures.png(true, false);
        when(ai.extract(any(), anyString())).thenReturn(new Extracted(true,
                "{\"furniture\":true,\"category\":\"chair\",\"features\":[\"loop arms\"],\"colors\":[\"blue seat\"]}", 1, 1));
        when(ai.generate(any(), any())).thenReturn(new Generated(sprite, 1, 1));
        when(ai.review(any(), any())).thenReturn(new Review(Decision.ACCEPT, "의자", "사용 가능", "", 1, 1));
        objects.clear(); objects.put("items/ref.png", sprite);
        when(storage.upload(any(), eq("image/png"), anyString())).thenAnswer(i -> {
            String key = i.getArgument(2) + "/" + UUID.randomUUID() + ".png";
            objects.put(key, i.getArgument(0)); return key;
        });
        when(storage.read(anyString())).thenAnswer(i -> new StoredAsset(objects.get(i.getArgument(0)), "image/png"));
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
    }

    @AfterEach void cleanup() {
        entries.deleteAll(); reservations.deleteAll(); purchases.deleteAll(); accounts.deleteAll();
        feedbacks.deleteAll(); jobs.deleteAll();
    }

    @Test void 검증된_거래만_지급하고_재전송과_다른계정_재사용을_차단() {
        var receipt = billing.confirm(user.getId(), Store.APPLE, "12345");
        assertThat(receipt.balance().available()).isEqualTo(3);
        assertThat(billing.confirm(user.getId(), Store.APPLE, "12345").id()).isEqualTo(receipt.id());
        verify(apple, times(1)).verify("12345");
        User other = users.save(User.signUp(UUID.randomUUID() + "@example.test"));
        assertCode(() -> billing.confirm(other.getId(), Store.APPLE, "12345"), "BILLING_PURCHASE_OWNER_MISMATCH");
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(purchases.findById(receipt.id()).orElseThrow().getReferenceEncrypted()).doesNotContain("12345");
    }

    @Test void 스토어_계정과_환경_상품은_클라이언트가_바꿀수_없음() {
        var wrong = new Verified(Store.APPLE, Environment.SANDBOX, "11", "pack3", UUID.randomUUID().toString(), 1, 1, true, now.toEpochMilli());
        assertCode(() -> credits.apply(user.getId(), wrong, cipher.encrypt("11")), "BILLING_PURCHASE_OWNER_MISMATCH");
        var production = new Verified(Store.APPLE, Environment.PRODUCTION, "12", "pack3", accountToken, 1, 1, true, now.toEpochMilli());
        assertCode(() -> credits.apply(user.getId(), production, cipher.encrypt("12")), "BILLING_PURCHASE_INVALID");
        assertCode(() -> credits.apply(user.getId(), verified(Store.APPLE, "13", "unlisted", 1), cipher.encrypt("13")), "BILLING_PRODUCT_UNAVAILABLE");
        assertThat(credits.balance(user.getId()).available()).isZero();
    }

    @Test void 생성권이_없으면_작업과_원본업로드를_만들지_않음() {
        assertCode(this::submit, "FURNITURE_CREDITS_REQUIRED");
        assertThat(jobs.count()).isZero();
        verify(storage, never()).upload(any(), anyString(), anyString());
        verify(ai, never()).extract(any(), anyString());
    }

    @Test void 작업_접수시_예약하고_중복요청은_추가예약_없이_성공시_확정() {
        grant("1");
        UUID requestId = UUID.randomUUID();
        var job = furniture.submit(user.getId(), requestId, "의자", photo());
        assertThat(credits.balance(user.getId()).available()).isEqualTo(2);
        assertThat(credits.balance(user.getId()).reserved()).isEqualTo(1);
        assertThat(furniture.submit(user.getId(), requestId, "의자", photo()).id()).isEqualTo(job.id());
        worker.runNext(); worker.runNext(); worker.runNext();
        assertThat(furniture.get(user.getId(), job.id()).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(credits.balance(user.getId()).available()).isEqualTo(2);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
    }

    @Test void 업로드실패는_예약한_생성권을_반환() {
        grant("1");
        doThrow(new IllegalStateException("storage failed")).when(storage).upload(any(), anyString(), anyString());
        assertThat(submit().status()).isEqualTo(Status.FAILED);
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
    }

    @Test void 공급자실패는_반복정리해도_한번만_반환() {
        grant("1");
        var job = submit();
        when(ai.extract(any(), anyString())).thenThrow(new FurnitureAiFailure("PROVIDER_RATE_LIMITED"));
        worker.runNext(); worker.maintain(); worker.maintain();
        assertThat(furniture.get(user.getId(), job.id()).status()).isEqualTo(Status.FAILED);
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
    }

    @Test void 검수실패_재시도는_추가차감_없이_한도소진시_반환() {
        grant("1");
        when(ai.review(any(), any())).thenReturn(new Review(Decision.EDIT, "의자", "다리 오류", "다리 수정", 1, 1));
        var job = submit();
        for (int i = 0; i < 7; i++) worker.runNext();
        assertThat(furniture.get(user.getId(), job.id()).status()).isEqualTo(Status.FAILED);
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
        verify(ai, times(3)).generate(any(), any());
    }

    @Test void 보관함_저장실패는_지급과_차감을_같이_롤백한뒤_예약반환() {
        grant("1");
        var job = submit(); worker.runNext(); worker.runNext();
        doThrow(new IllegalStateException("inventory failed")).when(inventory).save(any());
        worker.runNext();
        assertThat(furniture.get(user.getId(), job.id()).userItemId()).isNull();
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
    }

    @Test void 완성가구_피드백은_생성권을_추가차감하거나_반환하지_않음() {
        grant("1");
        var job = submit(); worker.runNext(); worker.runNext(); worker.runNext();
        when(ai.review(any(), any())).thenThrow(new FurnitureAiFailure("PROVIDER_RATE_LIMITED"));
        furniture.feedback(user.getId(), job.id(), new FurnitureFeedbackRequest(UUID.randomUUID(), "다리 확인"));
        worker.runNext();
        assertThat(credits.balance(user.getId()).available()).isEqualTo(2);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
        assertThat(furniture.get(user.getId(), job.id()).assetKey()).isNotNull();
    }

    @Test void 동시_결제확인과_웹훅은_한번만_지급() throws Exception {
        var verified = verified(Store.APPLE, "123", "pack3", 1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> {
                start.await(); return credits.apply(user.getId(), verified, cipher.encrypt("123"));
            }));
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(entries.count()).isEqualTo(1);
    }

    @Test void 스토어환불은_중복과_역순_구매알림에도_다시_지급되지_않음() {
        grant("123");
        var original = verified(Store.APPLE, "123", "pack3", 1);
        now = now.plusSeconds(1);
        var cancelled = verified(Store.APPLE, "123", "pack3", 0);
        credits.apply(null, cancelled, cipher.encrypt("123"));
        credits.apply(null, cancelled, cipher.encrypt("123"));
        credits.apply(null, original, cipher.encrypt("123"));
        assertThat(credits.balance(user.getId()).available()).isZero();
        assertThat(purchases.findAll().getFirst().getRevokedCredits()).isEqualTo(3);
    }

    @Test void 예약중_구매취소후_생성실패는_무료_생성권을_만들지_않음() {
        grant("123");
        submit();
        now = now.plusSeconds(1);
        credits.apply(null, verified(Store.APPLE, "123", "pack3", 0), cipher.encrypt("123"));
        assertThat(credits.balance(user.getId()).purchaseAdjustmentPending()).isTrue();
        when(ai.extract(any(), anyString())).thenThrow(new FurnitureAiFailure("PROVIDER_RATE_LIMITED"));
        worker.runNext();
        assertThat(credits.balance(user.getId()).available()).isZero();
        assertThat(credits.balance(user.getId()).reserved()).isZero();
        assertThat(credits.balance(user.getId()).purchaseAdjustmentPending()).isFalse();
    }

    @Test void Apple_환불취소는_복원하고_늦은_환불알림은_다시_회수하지_않음() {
        grant("123");
        now = now.plusSeconds(1);
        var refunded = verified(Store.APPLE, "123", "pack3", 0);
        credits.apply(null, refunded, cipher.encrypt("123"));
        now = now.plusSeconds(1);
        var reversed = verified(Store.APPLE, "123", "pack3", 1);
        credits.apply(null, reversed, cipher.encrypt("123"));
        credits.apply(null, reversed, cipher.encrypt("123"));
        credits.apply(null, refunded, cipher.encrypt("123"));
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(purchases.findAll().getFirst().getRevokedCredits()).isZero();
        assertThat(entries.count()).isEqualTo(3);
    }

    @Test void 서로다른_동시구매의_생성권이_덮어써지지_않음() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                String reference = Integer.toString(100 + i);
                tasks.add(executor.submit(() -> {
                    start.await();
                    return credits.apply(user.getId(), verified(Store.APPLE, reference, "pack3", 1), cipher.encrypt(reference));
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertThat(credits.balance(user.getId()).available()).isEqualTo(12);
        assertThat(purchases.count()).isEqualTo(4);
    }

    @Test void 대기작업_만료는_생성권을_한번만_반환() {
        grant("123");
        var job = submit();
        now = now.plus(Duration.ofHours(25));
        worker.maintain();
        worker.maintain();
        assertThat(furniture.get(user.getId(), job.id()).status()).isEqualTo(Status.FAILED);
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(credits.balance(user.getId()).reserved()).isZero();
        verify(ai, never()).extract(any(), anyString());
    }

    @Test void 지급후_Google_소비확인실패는_중복지급_없이_재처리() {
        var receipt = billing.confirm(user.getId(), Store.GOOGLE, "google-token");
        doThrow(new IllegalStateException("store timeout")).when(google).consume(any());
        billing.consumePending();
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
        assertThat(purchases.findById(receipt.id()).orElseThrow().isStoreConsumed()).isFalse();
        doNothing().when(google).consume(any());
        now = now.plusSeconds(121);
        billing.consumePending();
        assertThat(purchases.findById(receipt.id()).orElseThrow().isStoreConsumed()).isTrue();
        assertThat(credits.balance(user.getId()).available()).isEqualTo(3);
    }

    @Test void 생성권_API는_JWT가_필수이고_수량이나_사용자ID를_클라이언트가_지정하지_않음() throws Exception {
        mvc.perform(get("/api/v1/me/furniture-credits")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/furniture-credits/purchases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"store\":\"APPLE\",\"reference\":\"12345\"}"))
                .andExpect(status().isUnauthorized());
        String authorization = "Bearer " + tokens.issueAccessToken(user.getId(), MemberRole.NORMAL);
        mvc.perform(post("/api/v1/me/furniture-credits/purchases").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content("{\"store\":\"APPLE\",\"reference\":\"12345\",\"credits\":999999,\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.balance.available").value(3))
                .andExpect(jsonPath("$.referenceEncrypted").doesNotExist());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 잘못된_결제_JSON의_비밀값을_로그에_노출하지_않음(CapturedOutput output) throws Exception {
        String authorization = "Bearer " + tokens.issueAccessToken(user.getId(), MemberRole.NORMAL);
        mvc.perform(post("/api/v1/me/furniture-credits/purchases").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"store\":\"test-purchase-secret\",\"reference\":\"test-reference-secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(output.getAll()).doesNotContain("test-purchase-secret", "test-reference-secret");
        verify(apple, never()).verify(anyString());
        verify(google, never()).verify(anyString());
    }

    private Verified verified(Store store, String reference, String product, int refundable) {
        return new Verified(store, Environment.SANDBOX, reference, product, accountToken, 1, refundable,
                store == Store.APPLE, store == Store.APPLE ? now.toEpochMilli() : null);
    }
    private void grant(String reference) { credits.apply(user.getId(), verified(Store.APPLE, reference, "pack3", 1), cipher.encrypt(reference)); }
    private com.triples.rougether.userapi.furniture.dto.FurnitureGenerationResponse submit() {
        return furniture.submit(user.getId(), UUID.randomUUID(), "의자", photo());
    }
    private MockMultipartFile photo() { return new MockMultipartFile("photo", "chair.png", "image/png", sprite); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }
}
