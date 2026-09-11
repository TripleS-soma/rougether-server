package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.billing.entity.FurnitureCreditAccount;
import com.triples.rougether.domain.billing.repository.*;
import com.triples.rougether.domain.furniture.repository.*;
import com.triples.rougether.furniture.ai.*;
import com.triples.rougether.furniture.ai.FurnitureAiClient.*;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.lambda.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.furniture.service.*;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationService;
import com.triples.rougether.infra.assets.AssetStorageService;
import com.triples.rougether.infra.llm.LlmProperties;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** 실제 격리 MySQL + 로컬 HTTP 실패 주입. AWS·유료 AI 호출은 없음. */
@SpringBootTest(properties={"furniture.generation.enabled=true", "billing.require-credits=true",
        "billing.worker-enabled=false", "furniture.admission.max-outstanding=4", "furniture.admission.max-queue-wait=10s"})
class FurnitureStabilityIntegrationTest {
    @Autowired FurnitureGenerationTransactions tx;
    @Autowired FurnitureGenerationService service;
    @Autowired FurnitureLambdaControl control;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired FurnitureCreditAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean Clock kstClock;
    @MockitoBean AssetStorageService storage;
    volatile Instant now;
    final Map<String,Object> evidence=new LinkedHashMap<>();

    @BeforeEach void setup() {
        jdbc.update("delete from furniture_credit_reservations");
        jdbc.update("delete from furniture_generation_feedback");
        jobs.deleteAll();
        jdbc.update("update furniture_worker_capacity set execution_mode='LAMBDA',max_in_flight=2,execution_enabled=true where id=1");
        now=Instant.parse("2026-09-09T06:00:00Z");
        when(kstClock.instant()).thenAnswer(i->now);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }
    @AfterEach void cleanup(TestInfo info) throws Exception {
        Path dir=Path.of("build/stability-evidence");Files.createDirectories(dir);
        evidence.put("test",info.getTestMethod().orElseThrow().getName());
        evidence.put("scope","isolated MySQL; local HTTP provider; logical clock for expiry tests");
        JsonMapper.builder().build().writeValue(dir.resolve(info.getTestMethod().orElseThrow().getName()+".json").toFile(),evidence);
        jdbc.update("delete from furniture_credit_reservations");jobs.deleteAll();
        jdbc.update("update furniture_worker_capacity set execution_mode='RESIDENT',max_in_flight=1,execution_enabled=true where id=1");
    }
    User user() {
        var u=users.save(User.signUp("stability-"+UUID.randomUUID()+"@example.test"));
        var a=new FurnitureCreditAccount(u.getId());a.adjust(1);accounts.save(a);return u;
    }
    Message create() {
        var u=user();var r=tx.reserve(u.getId(),UUID.randomUUID().toString(),"digest","chair");
        tx.uploaded(u.getId(),r.job().id(),"private/furniture-generation/"+r.job().id()+"/source/a.png");
        return new Message(r.job().id(),jobs.findById(r.job().id()).orElseThrow().getExecutionId());
    }
    Result success(FurnitureGenerationTransactions.Claim c,String e) {
        return switch(c.action()) {
            case EXTRACT -> new Result(c.action(),new Extracted(true,"{}",1,1),null,null,false,0,0,null);
            case REVIEW -> new Result(c.action(),null,new Review(Decision.ACCEPT,"의자","ok","",1,1),"items/photo-furniture/furniture/"+e+"/a.png",true,0,0,null);
            default -> new Result(c.action(),null,null,"private/furniture-generation/"+c.id()+"/"+e+"/candidate/a.png",true,1,1,null);
        };
    }
    long entries(String id,String reason) {
        return jdbc.queryForObject("select count(*) from furniture_credit_entries where reference_id=? and reason=?",Long.class,id,reason);
    }
    @Test void sixteenConcurrentAdmissionsRespectGlobalFourAndRejectWithoutCharging() throws Exception {
        var us=new ArrayList<User>();for(int i=0;i<16;i++)us.add(user());
        var start=new CountDownLatch(1);var accepted=new AtomicInteger();var refused=new AtomicInteger();
        long t=System.nanoTime();
        try(var pool=Executors.newFixedThreadPool(16)) {
            var fs=us.stream().map(u->pool.submit(()->{
                start.await();
                try {tx.reserve(u.getId(),UUID.randomUUID().toString(),"digest","");accepted.incrementAndGet();}
                catch(BusinessException e){assertThat(e.getMessage()).contains("가구 생성 요청이 많습니다");refused.incrementAndGet();}
                return null;
            })).toList();start.countDown();for(var f:fs)f.get(15,TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(4);assertThat(refused).hasValue(12);
        assertThat(jobs.count()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from furniture_credit_reservations",Long.class)).isEqualTo(4);
        evidence.putAll(Map.of("attempts",16,"accepted",4,"rejectedWithoutReservation",12,"elapsedMs",(System.nanoTime()-t)/1e6));
    }
    @Test void fullQueueAllowsIdempotentReplayThenExpiresAndRefundsExactlyOnce() {
        var ms=new ArrayList<Message>();for(int i=0;i<4;i++)ms.add(create());
        var j=jobs.findById(ms.getFirst().jobId()).orElseThrow();
        assertThat(tx.reserve(j.getUserId(),j.getRequestId(),"digest","chair").created()).isFalse();
        now=now.plusSeconds(10);
        assertThat(tx.maintenanceCandidates()).containsAll(ms.stream().map(Message::jobId).toList());
        for(var m:ms) {
            tx.maintain(m.jobId());tx.maintain(m.jobId());
            assertThat(jobs.findById(m.jobId()).orElseThrow().getFailureCode()).isEqualTo("QUEUE_WAIT_EXCEEDED");
            assertThat(entries(m.jobId(),"RELEASE")).isEqualTo(1);
        }
        assertThat(create()).isNotNull();
        evidence.putAll(Map.of("expired",4,"releaseEntries",4,"logicalWaitSeconds",10,"newAdmissionAfterDrain",true));
    }
    @Test void lateQueueDeliveryNeverStartsAi() {
        var m=create();now=now.plusSeconds(10);var calls=new AtomicInteger();
        var runner=new FurnitureLambdaRunner(control::handle,(c,e)->{calls.incrementAndGet();return success(c,e);},Duration.ofSeconds(10));
        assertThat(runner.run(m,"late",()->600_000)).isTrue();
        assertThat(calls).hasValue(0);assertThat(entries(m.jobId(),"RELEASE")).isEqualTo(1);
        evidence.putAll(Map.of("aiStageCalls",0,"refunds",1));
    }
    @Test void feedbackCannotBypassFullQueue() {
        var m=create();new FurnitureLambdaRunner(control::handle,this::success,Duration.ofSeconds(10)).run(m,"first",()->600_000);
        var completed=jobs.findById(m.jobId()).orElseThrow();for(int i=0;i<4;i++)create();
        assertThatThrownBy(()->tx.feedback(completed.getUserId(),completed.getId(),UUID.randomUUID().toString(),"색 확인"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("가구 생성 요청이 많습니다");
        assertThat(jobs.findById(m.jobId()).orElseThrow().getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(entries(m.jobId(),"SPEND")).isEqualTo(1);
    }
    @Test void realHttp429IsNotRetriedAndRecoveryDoesNotDoubleSpend() throws Exception {
        var calls=new AtomicInteger();var limited=new AtomicBoolean(true);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",x->{
            calls.incrementAndGet();x.getRequestBody().readAllBytes();
            byte[] body=(limited.get()?"{\"error\":{\"message\":\"rate limit\"}}":"{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"furniture\\\":true,\\\"category\\\":\\\"chair\\\",\\\"features\\\":[\\\"round\\\"],\\\"colors\\\":[\\\"blue\\\"]}\"}]}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Retry-After","60");
            x.sendResponseHeaders(limited.get()?429:200,body.length);x.getResponseBody().write(body);x.close();
        });server.start();
        try {
            var llm=new LlmProperties("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fake","not-a-real-key",Duration.ofSeconds(10),null,800,true,"low",2,Duration.ofSeconds(1),"fake",1024);
            var cfg=new FurnitureGenerationProperties(true,"gpt-6-astra","fake",Duration.ofSeconds(10),3,6,Duration.ofHours(24),List.of("items/ref.png"));
            var ai=new OpenAiFurnitureClient(llm,cfg);
            var runner=new FurnitureLambdaRunner(control::handle,(c,e)->{
                if(c.action()==com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action.EXTRACT)ai.extract(new byte[]{1,2,3},"chair");
                return success(c,e);
            },Duration.ofSeconds(10));
            for(int i=0;i<2;i++) {
                var m=create();runner.run(m,"limited-"+i,()->600_000);runner.run(m,"redelivery-"+i,()->600_000);
                assertThat(jobs.findById(m.jobId()).orElseThrow().getFailureCode()).isEqualTo("PROVIDER_RATE_LIMITED");
                assertThat(entries(m.jobId(),"RELEASE")).isEqualTo(1);
            }
            assertThat(calls).hasValue(2);limited.set(false);
            var m=create();runner.run(m,"recovered",()->600_000);runner.run(m,"duplicate",()->600_000);
            assertThat(entries(m.jobId(),"SPEND")).isEqualTo(1);assertThat(calls).hasValue(3);
            evidence.putAll(Map.of("http429",2,"httpCallsIncludingRecovery",3,"refunds",2,"recoverySpends",1,"automaticProviderRetries",0));
        } finally {server.stop(0);}
    }
    @Test void capacityLockWaitTimesOutWhileOrdinaryReadsContinue() throws Exception {
        var u=user();byte[] photo=FurnitureFixtures.png(true,false);
        try(var lock=dataSource.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);lock.createStatement().executeQuery("select id from furniture_worker_capacity where id=1 for update").close();
            long t=System.nanoTime();
            var blocked=pool.submit(()->service.submit(u.getId(),UUID.randomUUID(),"chair",new MockMultipartFile("photo","chair.png","image/png",photo)));
            int reads=0;long maxRead=0;
            try {
                while(!blocked.isDone() && System.nanoTime()-t<TimeUnit.SECONDS.toNanos(9)) {
                    long rt=System.nanoTime();assertThat(tx.list(u.getId())).isEmpty();maxRead=Math.max(maxRead,System.nanoTime()-rt);reads++;Thread.sleep(50);
                }
                assertThatThrownBy(()->blocked.get(1,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
                double seconds=(System.nanoTime()-t)/1e9;assertThat(seconds).isLessThan(8);
                assertThat(jobs.count()).isZero();verifyNoInteractions(storage);
                evidence.putAll(Map.of("blockedAdmissionSeconds",seconds,"ordinaryReads",reads,"ordinaryMaxMs",maxRead/1e6,"reservations",0));
            } finally {lock.rollback();}
        }
    }
}
