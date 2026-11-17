package com.triples.rougether.userapi.furniture;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.billing.entity.FurnitureCreditAccount;
import com.triples.rougether.domain.billing.repository.FurnitureCreditAccountRepository;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"billing.require-credits=true", "billing.worker-enabled=false",
        "furniture.generation.enabled=true", "furniture.upload.multipart-lambda-enabled=true"})
class FurnitureDirectUploadIntegrationTest {
    @Autowired FurnitureGenerationTransactions tx;
    @Autowired com.triples.rougether.furniture.lambda.FurnitureLambdaControl control;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired FurnitureCreditAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock kstClock;
    @MockitoBean software.amazon.awssdk.services.s3.S3Client s3;
    @MockitoBean com.triples.rougether.furniture.service.FurnitureImages images;
    @Autowired com.triples.rougether.userapi.furniture.service.FurnitureGenerationService service;
    volatile Instant now;
    Long user;
    @BeforeEach void setup() {
        jdbc.update("delete from furniture_credit_reservations");
        jdbc.update("delete from furniture_generation_feedback");
        jobs.deleteAll();
        now = Instant.parse("2026-09-11T00:00:00Z");
        doAnswer(i -> now).when(kstClock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        user = users.save(User.signUp("upload-" + UUID.randomUUID() + "@example.test")).getId();
        var account = new FurnitureCreditAccount(user); account.adjust(1); accounts.save(account);
    }
    @AfterEach void cleanupReservation() {
        jdbc.update("delete from furniture_credit_reservations where user_id=?", user);
    }
    FurnitureGenerationTransactions.UploadReservation reserve(String request) {
        return tx.reserveDirect(user, request, "a".repeat(64), 123, "image/jpeg", "한글 힌트");
    }
    Command start(String job) { return Command.start(job, FurniturePreprocessProtocol.sourceKey(job), "source-v1"); }
    Command complete(Command start) { return start.complete(FurniturePreprocessProtocol.outputKey(start.jobId()), "png-v1", "b".repeat(64)); }
    long entries(String job, String reason) {
        return jdbc.queryForObject("select count(*) from furniture_credit_entries where reference_id=? and reason=?", Long.class, job, reason);
    }

    org.springframework.mock.web.MockMultipartFile rawPhoto() {
        return new org.springframework.mock.web.MockMultipartFile("photo", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2});
    }

    @Test void 구버전_앱의_사진도_디코딩없이_스트림으로_올리고_전처리_완료후에만_AI를_등록함() throws Exception {
        when(s3.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class))).thenAnswer(i -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            software.amazon.awssdk.services.s3.model.PutObjectRequest put = i.getArgument(0);
            assertThat(put.ifNoneMatch()).isEqualTo("*");
            assertThat(put.contentType()).isEqualTo("image/png");
            assertThat(put.checksumSHA256()).isNotBlank();
            software.amazon.awssdk.core.sync.RequestBody body = i.getArgument(1);
            try (var first = body.contentStreamProvider().newStream(); var retry = body.contentStreamProvider().newStream()) {
                first.read();
                assertThat(retry.readAllBytes()).isEqualTo(rawPhoto().getBytes());
            }
            return software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().versionId("source-v1").build();
        });
        UUID request = UUID.randomUUID();
        var first = service.submit(user, request, "한글 힌트", rawPhoto());
        assertThat(first.status()).isEqualTo(FurnitureGenerationJob.Status.UPLOADING);
        assertThat(jobs.findById(first.id()).orElseThrow().getExecutionId()).isNull();
        var duplicate = service.submit(user, request, "한글 힌트", rawPhoto());
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(entries(first.id(), "RESERVE")).isEqualTo(1);
        verifyNoInteractions(images);
        var start = start(first.id());
        tx.preprocess(start); tx.preprocess(complete(start)); tx.preprocess(complete(start));
        assertThat(jobs.findById(first.id()).orElseThrow().getStatus()).isEqualTo(FurnitureGenerationJob.Status.QUEUED);
        assertThat(jdbc.queryForObject("select count(*) from furniture_lambda_execution where job_id=?", Long.class, first.id())).isEqualTo(1);
    }

    @Test void PUT_응답유실은_즉시환불하지_않고_같은요청의_412를_수용한뒤_미완료시_한번_환불함() {
        when(s3.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("response lost"))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(412).build());
        UUID request = UUID.randomUUID();
        assertThatThrownBy(() -> service.submit(user, request, "", rawPhoto())).isInstanceOf(BusinessException.class);
        var stored = jobs.findByUserIdAndRequestId(user, request.toString()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(FurnitureGenerationJob.Status.UPLOADING);
        assertThat(entries(stored.getId(), "RELEASE")).isZero();
        assertThat(service.submit(user, request, "", rawPhoto()).id()).isEqualTo(stored.getId());
        assertThat(entries(stored.getId(), "RESERVE")).isEqualTo(1);
        now = now.plusSeconds(1801);
        tx.maintain(stored.getId()); tx.maintain(stored.getId());
        assertThat(entries(stored.getId(), "RELEASE")).isEqualTo(1);
        verifyNoInteractions(images);
    }

    @Test void 전환전에_예약한_동일_multipart는_새방식으로_덮어쓰거나_재예약하지_않음() throws Exception {
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        hash.update(rawPhoto().getBytes()); hash.update((byte) 0);
        String digest = HexFormat.of().formatHex(hash.digest("hint".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        UUID request = UUID.randomUUID();
        var old = tx.reserve(user, request.toString(), digest, "hint");
        assertThat(service.submit(user, request, "hint", rawPhoto()).id()).isEqualTo(old.job().id());
        assertThat(jobs.findById(old.job().id()).orElseThrow().getRawSourceKey()).isNull();
        assertThat(entries(old.job().id(), "RESERVE")).isEqualTo(1);
        verifyNoInteractions(s3, images);
        assertThatThrownBy(() -> service.submit(user, request, "changed", rawPhoto())).isInstanceOf(BusinessException.class);
    }
    @Test void 구버전_앱의_동시_재전송도_생성권을_한번만_예약함() throws Exception {
        when(s3.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().versionId("source-v1").build());
        UUID request = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> service.submit(user, request, "", rawPhoto()).id()));
            var ids = new HashSet<String>();
            for (var task : tasks) ids.add(task.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(entries(ids.iterator().next(), "RESERVE")).isEqualTo(1);
        }
        verifyNoInteractions(images);
    }

    @Test void 중계_메타데이터가_유효하지_않으면_예약하지_않음() {
        assertThatThrownBy(() -> tx.reserveMultipart(user, UUID.randomUUID().toString(), "a".repeat(64),
                "b".repeat(64), 10, null, "")).isInstanceOf(BusinessException.class);
        assertThat(accounts.findById(user).orElseThrow().getReserved()).isZero();
    }

    @Test void 같은_예약은_한번만_차감하고_내용이_바뀌면_거절함() {
        String request = UUID.randomUUID().toString();
        var first = reserve(request); var duplicate = reserve(request);
        assertThat(duplicate.job().id()).isEqualTo(first.job().id());
        assertThat(entries(first.job().id(), "RESERVE")).isEqualTo(1);
        assertThatThrownBy(() -> tx.reserveDirect(user, request, "a".repeat(64), 124, "image/jpeg", "한글 힌트"))
                .isInstanceOf(BusinessException.class);
        assertThat(jobs.findById(first.job().id()).orElseThrow().getExecutionId()).isNull();
    }
    @Test void 여러_완료_알림이_겹쳐도_생성_Outbox는_하나이며_늦은_실패도_무시함() throws Exception {
        var ticket = reserve(UUID.randomUUID().toString()); var start = start(ticket.job().id());
        assertThat(tx.preprocess(start).status()).isEqualTo(Status.READY);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<Reply>>();
            for (int i = 0; i < 16; i++) tasks.add(pool.submit(() -> tx.preprocess(complete(start))));
            for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS).status()).isEqualTo(Status.DONE);
        }
        tx.preprocess(start.fail("FURNITURE_PHOTO_INVALID"));
        var job = jobs.findById(ticket.job().id()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(FurnitureGenerationJob.Status.QUEUED);
        assertThat(job.getSourceKey()).isEqualTo(FurniturePreprocessProtocol.outputKey(job.getId()));
        assertThat(jdbc.queryForObject("select count(*) from furniture_lambda_execution where job_id=?", Long.class, job.getId())).isEqualTo(1);
        assertThat(entries(job.getId(), "RESERVE")).isEqualTo(1);
        assertThat(accounts.findById(user).orElseThrow().getReserved()).isEqualTo(1);
    }
    @Test void 원본의_다른_버전과_선점_없는_완료는_무시함() {
        var job = reserve(UUID.randomUUID().toString()).job().id(); var start = start(job);
        assertThat(tx.preprocess(complete(start)).status()).isEqualTo(Status.STALE);
        tx.preprocess(start);
        assertThat(tx.preprocess(Command.start(job, start.sourceKey(), "source-v2")).status()).isEqualTo(Status.STALE);
        assertThatThrownBy(() -> tx.preprocess(start.complete("items/other.png", "v", "b".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.findById(job).orElseThrow().getExecutionId()).isNull();
    }
    @Test void 미업로드_만료는_환불하고_늦은_완료는_실행을_등록하지_않음() {
        var job = reserve(UUID.randomUUID().toString()).job().id(); var start = start(job);
        tx.preprocess(start);
        now = now.plusSeconds(1801);
        assertThat(tx.maintenanceCandidates()).contains(job);
        tx.maintain(job); tx.maintain(job); tx.preprocess(complete(start));
        assertThat(jobs.findById(job).orElseThrow().getStatus()).isEqualTo(FurnitureGenerationJob.Status.FAILED);
        assertThat(accounts.findById(user).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(accounts.findById(user).orElseThrow().getReserved()).isZero();
        assertThat(jobs.findById(job).orElseThrow().getExecutionId()).isNull();
    }
    @Test void 형식_실패와_탈퇴시_단일_환불하며_원본도_정리대상임() {
        var job = reserve(UUID.randomUUID().toString()).job().id(); var start = start(job);
        tx.preprocess(start);
        tx.preprocess(start.fail("FURNITURE_PHOTO_INVALID")); tx.preprocess(start.fail("FURNITURE_PHOTO_INVALID"));
        assertThat(accounts.findById(user).orElseThrow().getBalance()).isEqualTo(1);
        jdbc.update("update users set deleted_at=? where id=?", java.sql.Timestamp.from(now), user);
        assertThat(tx.maintenanceCandidates()).contains(job);
        assertThat(tx.maintain(job).rawSourceKey()).isEqualTo(start.sourceKey());
    }

    @Test void 탈퇴한_사용자의_늦은_업로드는_읽기전에_거절하고_환불함() {
        var job = reserve(UUID.randomUUID().toString()).job().id();
        jdbc.update("update users set deleted_at=? where id=?", java.sql.Timestamp.from(now), user);
        assertThat(tx.preprocess(start(job)).status()).isEqualTo(Status.TERMINAL);
        assertThat(jobs.findById(job).orElseThrow().getExecutionId()).isNull();
        assertThat(accounts.findById(user).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(entries(job, "RELEASE")).isEqualTo(1);
    }

    @Test void 원본_보존기간이_끝나도_같은_요청은_옛_작업을_반환하고_다시_차감하지_않음() {
        String request = UUID.randomUUID().toString(); var first = reserve(request);
        now = now.plusSeconds(86401);
        var cleanup = tx.maintain(first.job().id()); tx.cleaned(cleanup);
        var retry = reserve(request);
        assertThat(retry.job().id()).isEqualTo(first.job().id());
        assertThat(retry.job().status()).isEqualTo(FurnitureGenerationJob.Status.FAILED);
        assertThat(retry.rawKey()).isNull();
        assertThat(entries(first.job().id(), "RESERVE")).isEqualTo(1);
        assertThat(entries(first.job().id(), "RELEASE")).isEqualTo(1);
    }

    @Test void 직접업로드_완료가_기존_AI_상태머신에_연결되어_아이템과_생성권을_한번_확정함() {
        jdbc.update("update furniture_worker_capacity set execution_mode='LAMBDA', max_in_flight=2, execution_enabled=true where id=1");
        try {
            String id = reserve(UUID.randomUUID().toString()).job().id(); var start = start(id);
            tx.preprocess(start); tx.preprocess(complete(start));
            String execution = jobs.findById(id).orElseThrow().getExecutionId();
            var message = new com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Message(id, execution);
            var stageCalls = new java.util.concurrent.atomic.AtomicInteger();
            var runner = new com.triples.rougether.furniture.lambda.FurnitureLambdaRunner(control::handle, (claim, run) -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(claim.sourceKey()).isEqualTo(FurniturePreprocessProtocol.outputKey(id));
                stageCalls.incrementAndGet();
                return switch (claim.action()) {
                    case EXTRACT -> new com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Result(claim.action(),
                            new com.triples.rougether.furniture.ai.FurnitureAiClient.Extracted(true, "{}", 1, 1), null, null, false, 0, 0, null);
                    case REVIEW -> new com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Result(claim.action(), null,
                            new com.triples.rougether.furniture.ai.FurnitureAiClient.Review(
                                    com.triples.rougether.furniture.ai.FurnitureAiClient.Decision.ACCEPT, "의자", "ok", "", 1, 1),
                            "items/photo-furniture/furniture/" + run + "/test.png", true, 0, 0, null);
                    default -> new com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Result(claim.action(), null, null,
                            "private/furniture-generation/" + id + "/" + run + "/candidate/test.png", true, 1, 1, null);
                };
            }, Duration.ofSeconds(30));
            runner.run(message, "first", () -> 600_000);
            tx.preprocess(complete(start));
            runner.run(message, "redelivery", () -> 600_000);
            var job = jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(FurnitureGenerationJob.Status.SUCCEEDED);
            assertThat(job.getUserItemId()).isNotNull();
            assertThat(stageCalls).hasValue(3);
            assertThat(entries(id, "SPEND")).isEqualTo(1);
            assertThat(accounts.findById(user).orElseThrow().getBalance()).isZero();
            assertThat(accounts.findById(user).orElseThrow().getReserved()).isZero();
        } finally {
            jdbc.update("update furniture_worker_capacity set execution_mode='RESIDENT', max_in_flight=1, execution_enabled=true where id=1");
        }
    }
}
