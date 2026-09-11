package com.triples.rougether.userapi.furniture;

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

@SpringBootTest(properties = {"billing.require-credits=true", "billing.worker-enabled=false"})
class FurnitureDirectUploadIntegrationTest {
    @Autowired FurnitureGenerationTransactions tx;
    @Autowired com.triples.rougether.furniture.lambda.FurnitureLambdaControl control;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired FurnitureCreditAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock kstClock;
    volatile Instant now;
    Long user;
    @BeforeEach void setup() {
        jdbc.update("delete from furniture_credit_reservations");
        jdbc.update("delete from furniture_generation_feedback");
        jobs.deleteAll();
        now = Instant.parse("2026-09-11T00:00:00Z");
        when(kstClock.instant()).thenAnswer(i -> now);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
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
