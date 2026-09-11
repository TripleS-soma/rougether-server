package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.billing.entity.*;
import com.triples.rougether.domain.billing.repository.*;
import com.triples.rougether.furniture.lambda.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.furniture.ai.FurnitureAiClient.*;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {"billing.require-credits=true", "billing.worker-enabled=false"})
class FurnitureLambdaIntegrationTest {
    @Autowired FurnitureLambdaControl control;
    @Autowired FurnitureLambdaOutbox outbox;
    @Autowired FurnitureGenerationTransactions tx;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired FurnitureLambdaExecutionRepository executions;
    @Autowired FurnitureGenerationFeedbackRepository feedbacks;
    @Autowired UserRepository users;
    @Autowired FurnitureCreditAccountRepository accounts;
    @Autowired FurnitureCreditReservationRepository reservations;
    @Autowired FurnitureCreditEntryRepository entries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean Clock kstClock;
    Instant now;
    @BeforeEach void setup() {
        now = Instant.parse("2026-09-09T03:00:00Z");
        when(kstClock.instant()).thenAnswer(i -> now);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        reservations.deleteAll(); feedbacks.deleteAll(); jobs.deleteAll();
        jdbc.update("update furniture_worker_capacity set execution_mode='LAMBDA', max_in_flight=2, execution_enabled=true where id=1");
    }
    @AfterEach void resetMode() {
        reservations.deleteAll();
        jdbc.update("update furniture_worker_capacity set execution_mode='RESIDENT', max_in_flight=1, execution_enabled=true where id=1");
    }
    Message create() {
        var user = users.save(User.signUp("lambda-" + UUID.randomUUID() + "@example.test"));
        var account = new FurnitureCreditAccount(user.getId()); account.adjust(1); accounts.save(account);
        var reservation = tx.reserve(user.getId(), UUID.randomUUID().toString(), "digest", "chair");
        String id = reservation.job().id();
        assertThat(tx.uploaded(user.getId(), id, "private/furniture-generation/" + id + "/source/a.png")).isTrue();
        return new Message(id, jobs.findById(id).orElseThrow().getExecutionId());
    }
    Command start(Message m, String owner) { return new Command(m.jobId(), m.executionId(), owner, 0, null); }
    Result result(Claim claim, String execution) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return switch (claim.action()) {
            case EXTRACT -> new Result(claim.action(), new Extracted(true, "{}", 2, 1), null, null, false, 0, 0, null);
            case REVIEW -> new Result(claim.action(), null, new Review(Decision.ACCEPT, "의자", "ok", "", 2, 1),
                    "items/photo-furniture/furniture/" + execution + "/a.png", true, 0, 0, null);
            default -> new Result(claim.action(), null, null, "private/furniture-generation/" + claim.id() + "/" + execution + "/candidate/a.png", true, 2, 1, null);
        };
    }
    @Test void 실제_이미지_단계를_거쳐_완료하고_피드백은_새_실행으로_분리() {
        var m = create();
        var config = new com.triples.rougether.furniture.config.FurnitureGenerationProperties(true,
                "gpt-6-astra", "test-image", Duration.ofSeconds(30), 3, 6, Duration.ofHours(24), List.of("items/ref.png"));
        var ai = mock(com.triples.rougether.furniture.ai.FurnitureAiClient.class);
        var storage = mock(com.triples.rougether.infra.assets.AssetStorageService.class);
        byte[] png = FurnitureFixtures.png(true, false);
        var objects = new HashMap<String, byte[]>();
        objects.put(jobs.findById(m.jobId()).orElseThrow().getSourceKey(), png);
        objects.put("items/ref.png", png);
        when(ai.available()).thenReturn(true);
        when(ai.extract(any(), anyString())).thenReturn(new Extracted(true, "{}", 1, 1));
        when(ai.generate(any(), any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((Context) i.getArgument(0)).source()).isNull();
            return new Generated(png, 1, 1);
        });
        when(ai.review(any(), any())).thenAnswer(i -> {
            assertThat(((Context) i.getArgument(0)).source()).isNotNull();
            return new Review(Decision.ACCEPT, "의자", "ok", "", 1, 1);
        });
        when(storage.read(anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.triples.rougether.infra.assets.StoredAsset(objects.get(i.getArgument(0)), "image/png");
        });
        when(storage.upload(any(), anyString(), anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String key = i.getArgument(2) + "/" + UUID.randomUUID() + ".png";
            objects.put(key, i.getArgument(0)); return key;
        });
        var stage = new FurnitureLambdaStage(ai, new com.triples.rougether.furniture.service.FurnitureImages(), storage, config);
        var runner = new FurnitureLambdaRunner(control::handle, stage::execute, config.timeout());
        runner.run(m, "first", () -> 600_000);
        var job = jobs.findById(m.jobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(FurnitureGenerationJob.Status.SUCCEEDED);
        String finalKey = job.getResultAssetKey(); Long itemId = job.getUserItemId();
        String request = UUID.randomUUID().toString();
        tx.feedback(job.getUserId(), job.getId(), request, "색 확인");
        var next = new Message(job.getId(), jobs.findById(job.getId()).orElseThrow().getExecutionId());
        assertThat(next.executionId()).isNotEqualTo(m.executionId());
        assertThat(control.handle(start(m, "old-retry")).status()).isEqualTo(Status.STALE);
        tx.feedback(job.getUserId(), job.getId(), request, "색 확인");
        assertThat(jobs.findById(job.getId()).orElseThrow().getExecutionId()).isEqualTo(next.executionId());
        runner.run(next, "feedback", () -> 600_000);
        var revised = jobs.findById(job.getId()).orElseThrow();
        assertThat(revised.getStatus()).isEqualTo(FurnitureGenerationJob.Status.SUCCEEDED);
        assertThat(revised.getResultAssetKey()).isEqualTo(finalKey);
        assertThat(revised.getUserItemId()).isEqualTo(itemId);
        verify(ai, times(1)).extract(any(), anyString());
        verify(ai, times(1)).generate(any(), any());
        verify(ai, times(2)).review(any(), any());
    }

    @Test void 캐시된_시작응답도_사용자_탈퇴후에는_실행권을_주지않음() {
        var m = create(); var command = start(m, "owner");
        assertThat(control.handle(command).status()).isEqualTo(Status.READY);
        jdbc.update("update users set deleted_at=? where id=?", java.sql.Timestamp.from(now), jobs.findById(m.jobId()).orElseThrow().getUserId());
        assertThat(control.handle(command).status()).isEqualTo(Status.TERMINAL);
        assertThat(jobs.findById(m.jobId()).orElseThrow().getFailureCode()).isEqualTo("OWNER_WITHDRAWN");
    }

    @Test void 같은_메시지가_동시에_와도_실행은_하나() throws Exception {
        var m = create();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var barrier = new CountDownLatch(1);
            var futures = new ArrayList<Future<Reply>>();
            for (int i = 0; i < 8; i++) {
                String owner = "owner-" + i;
                futures.add(pool.submit(() -> { barrier.await(); return control.handle(start(m, owner)); }));
            }
            barrier.countDown();
            var replies = new ArrayList<Reply>();
            for (var f : futures) replies.add(f.get(15, TimeUnit.SECONDS));
            assertThat(replies.stream().filter(r -> r.status() == Status.READY)).hasSize(1);
            assertThat(replies.stream().filter(r -> r.status() == Status.DUPLICATE)).hasSize(7);
        }
        assertThat(jobs.findById(m.jobId()).orElseThrow().getExtractionAttempts()).isEqualTo(1);
        assertThat(tx.claim(m.jobId())).isNull();
    }
    @Test void 여러_작업의_동시_시작은_전체_상한을_지킴() throws Exception {
        var messages = new ArrayList<Message>();
        for (int i = 0; i < 8; i++) messages.add(create());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = messages.stream().map(m -> pool.submit(() -> control.handle(start(m, UUID.randomUUID().toString())))).toList();
            int ready = 0;
            for (var f : futures) if (f.get(15, TimeUnit.SECONDS).status() == Status.READY) ready++;
            assertThat(ready).isEqualTo(2);
        }
        assertThat(jobs.countByStatus(FurnitureGenerationJob.Status.PROCESSING)).isEqualTo(2);
    }
    @Test void 모든_커밋의_응답_유실에도_AI와_아이템과_차감은_한번() {
        var m = create();
        var lost = new HashSet<Integer>();
        var calls = new AtomicInteger();
        var runner = new FurnitureLambdaRunner(c -> {
            var reply = control.handle(c);
            if (lost.add(c.sequence())) throw new IllegalStateException("commit response lost");
            return reply;
        }, (claim, execution) -> { calls.incrementAndGet(); return result(claim, execution); }, Duration.ofSeconds(30));
        assertThat(runner.run(m, "owner", () -> 600_000)).isTrue();
        assertThat(runner.run(m, "redelivery", () -> 600_000)).isTrue();
        var job = jobs.findById(m.jobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(FurnitureGenerationJob.Status.SUCCEEDED);
        assertThat(job.getUserItemId()).isNotNull();
        assertThat(calls).hasValue(3);
        assertThat(reservations.findById(m.jobId()).orElseThrow().getStatus()).isEqualTo(FurnitureCreditReservation.Status.SPENT);
        assertThat(entries.findAll().stream().filter(e -> m.jobId().equals(e.getReferenceId()) && e.getReason() == FurnitureCreditEntry.Reason.SPEND)).hasSize(1);
    }
    @Test void 결과_저장_불명확하면_AI_재실행없이_만료후_한번_환불() {
        var m = create(); var calls = new AtomicInteger();
        var runner = new FurnitureLambdaRunner(c -> {
            if (c.sequence() > 0) throw new IllegalStateException("DB unavailable");
            return control.handle(c);
        }, (claim, execution) -> { calls.incrementAndGet(); return result(claim, execution); }, Duration.ofSeconds(30));
        assertThatThrownBy(() -> runner.run(m, "first", () -> 600_000)).isInstanceOf(IllegalStateException.class);
        assertThat(runner.run(m, "second", () -> 600_000)).isTrue();
        assertThat(calls).hasValue(1);
        now = now.plusSeconds(661);
        tx.maintain(m.jobId()); tx.maintain(m.jobId());
        var job = jobs.findById(m.jobId()).orElseThrow();
        assertThat(job.getFailureCode()).isEqualTo("WORKER_INTERRUPTED");
        assertThat(accounts.findById(job.getUserId()).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(entries.findAll().stream().filter(e -> m.jobId().equals(e.getReferenceId()) && e.getReason() == FurnitureCreditEntry.Reason.RELEASE)).hasSize(1);
    }
    @Test void outbox는_업로드_트랜잭션과_함께_롤백되고_전송은_재시도됨() {
        var m = create();
        var dispatch = outbox.reserve(m.executionId());
        assertThat(dispatch).isNotNull();
        assertThat(outbox.reserve(m.executionId())).isNull();
        now = now.plusSeconds(61);
        var retry = outbox.reserve(m.executionId());
        assertThat(retry.token()).isNotEqualTo(dispatch.token());
        outbox.sent(dispatch);
        assertThat(executions.findById(m.executionId()).orElseThrow().getState().name()).isEqualTo("PENDING");
        outbox.sent(retry);
        assertThat(executions.findById(m.executionId()).orElseThrow().getState().name()).isEqualTo("SENT");
        long before = executions.count();
        new TransactionTemplate(manager).executeWithoutResult(status -> { create(); status.setRollbackOnly(); });
        assertThat(executions.count()).isEqualTo(before);
    }
    @Test void 시간부족이면_AI를_시작하지않고_환불() {
        var m = create(); var calls = new AtomicInteger();
        var runner = new FurnitureLambdaRunner(control::handle, (claim, execution) -> {
            calls.incrementAndGet(); return result(claim, execution);
        }, Duration.ofSeconds(180));
        runner.run(m, "owner", () -> 1000);
        assertThat(calls).hasValue(0);
        assertThat(jobs.findById(m.jobId()).orElseThrow().getFailureCode()).isEqualTo("EXECUTION_TIME_BUDGET_EXHAUSTED");
    }
    @Test void 다른_실행의_에셋과_동일순번의_변조된_결과는_거부() {
        var m = create(); var started = control.handle(start(m, "owner"));
        var checkpoint = new Command(m.jobId(), m.executionId(), "owner", 1, result(started.claim(), m.executionId()));
        var next = control.handle(checkpoint);
        assertThat(control.handle(checkpoint)).isEqualTo(next);
        assertThatThrownBy(() -> control.handle(new Command(m.jobId(), m.executionId(), "owner", 1,
                Result.failure(started.claim().action(), "OTHER_FAILURE")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.handle(new Command(m.jobId(), m.executionId(), "owner", 2,
                result(next.claim(), "another-execution")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.findById(m.jobId()).orElseThrow().getImageAttempts()).isEqualTo(1);
    }
}
