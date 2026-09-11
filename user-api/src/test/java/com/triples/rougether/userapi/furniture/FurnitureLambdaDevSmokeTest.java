package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.billing.entity.FurnitureCreditAccount;
import com.triples.rougether.domain.billing.repository.FurnitureCreditAccountRepository;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationService;
import com.triples.rougether.infra.assets.AssetStorageService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** 실제 유료 두 작업. 명시적 승인 후 테스트 전용 DB/함수/큐에만 실행함. */
@EnabledIfEnvironmentVariable(named="FURNITURE_LAMBDA_DEV_SMOKE", matches="true")
@SpringBootTest(properties={
        "spring.datasource.url=${FURNITURE_DEV_JDBC_URL}", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${FURNITURE_DEV_DB_USER}", "spring.datasource.password=${FURNITURE_DEV_DB_PASSWORD}",
        "furniture.generation.enabled=true", "furniture.lambda.dispatch-enabled=true",
        "furniture.lambda.queue-url=${FURNITURE_DEV_QUEUE_URL}", "billing.require-credits=true",
        "billing.worker-enabled=false", "furniture.generation.worker-enabled=false",
        "asset.s3.bucket=${FURNITURE_DEV_ASSET_BUCKET}", "furniture.lambda.dispatch-delay=1000"})
class FurnitureLambdaDevSmokeTest {
    @Autowired FurnitureGenerationService service;
    @Autowired UserRepository users;
    @Autowired FurnitureCreditAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired AssetStorageService storage;
    final JsonMapper json=JsonMapper.builder().build();

    @Test void schemaOnly() {
        assertThat(jdbc.queryForObject("select database()",String.class)).startsWith("rougether_furniture_bench_");
        assertThat(jdbc.queryForObject("select count(*) from furniture_generation_jobs",Long.class)).isZero();
        jdbc.update("update furniture_worker_capacity set execution_mode='LAMBDA', max_in_flight=2, execution_enabled=false where id=1");
    }

    @Test void 실제_Lambda_두_작업의_완료와_단일정산을_측정() throws Exception {
        Path output=Path.of(System.getenv("FURNITURE_DEV_OUTPUT"));
        Path samples=Path.of(System.getenv("FURNITURE_LIVE_OUTPUT"));
        Files.createDirectories(output);
        assertThat(jdbc.queryForObject("select database()",String.class)).startsWith("rougether_furniture_bench_");
        assertThat(jdbc.queryForObject("select count(*) from furniture_generation_jobs",Long.class)).isZero();
        jdbc.update("update furniture_worker_capacity set execution_mode='LAMBDA', max_in_flight=2, execution_enabled=false where id=1");
        var jobs=new LinkedHashMap<String,Long>(); var labels=new HashMap<String,String>();
        var finished=new LinkedHashMap<String,FurnitureGenerationResponse>();
        var elapsed=new LinkedHashMap<String,Double>();
        var result=new LinkedHashMap<String,Object>();
        try {
            for(int i=0;i<2;i++) {
                String slug=i==0?"mac-mini":"bear-cake";
                var user=users.save(User.signUp("lambda-dev-"+UUID.randomUUID()+"@example.test"));
                var account=new FurnitureCreditAccount(user.getId()); account.adjust(1); accounts.save(account);
                String hint=i==0?"사진의 은색 맥 미니 본체 하나":"사진의 산타 모자를 쓴 흰 곰 얼굴 케이크 하나";
                var job=service.submit(user.getId(),UUID.randomUUID(),hint,new MockMultipartFile(
                        "photo","upload.jpg","image/jpeg",Files.readAllBytes(samples.resolve(slug).resolve("upload.jpg"))));
                jobs.put(job.id(),user.getId());labels.put(job.id(),slug);
            }
            result.put("startedAt",Instant.now().toString()); long start=System.nanoTime();
            jdbc.update("update furniture_worker_capacity set execution_enabled=true where id=1");
            while(finished.size()<2 && System.nanoTime()-start<TimeUnit.SECONDS.toNanos(720)) {
                for(var entry:jobs.entrySet()) {
                    if(finished.containsKey(entry.getKey()))continue;
                    var job=service.get(entry.getValue(),entry.getKey());
                    if(job.status().name().equals("SUCCEEDED") || job.status().name().equals("FAILED")) {
                        finished.put(job.id(),job); elapsed.put(job.id(),(System.nanoTime()-start)/1e9);
                    }
                }
                Thread.sleep(500);
            }
        } finally {
            jdbc.update("update furniture_worker_capacity set execution_enabled=false where id=1");
            result.put("endedAt",Instant.now().toString()); result.put("terminalJobs",finished);
            result.put("completionSeconds",elapsed);result.put("labels",labels);
            result.put("executions",jdbc.queryForList("select id,job_id,owner,state,sequence from furniture_lambda_execution"));
            result.put("credits",jdbc.queryForList("select job_id,user_id,status from furniture_credit_reservations"));
            result.put("usage",jdbc.queryForList("select id,input_tokens,output_tokens,image_attempts,review_attempts,extraction_attempts from furniture_generation_jobs"));
            result.put("intakePlacement","local application service -> DEV RDS/S3/SQS/Lambda; not deployed DEV API ingress");
            json.writeValue(output.resolve("result.json").toFile(),result);
        }
        assertThat(finished).hasSize(2);
        for(var job:finished.values()) {
            assertThat(job.status().name()).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("select count(*) from furniture_credit_entries where reference_id=? and reason='SPEND'",Long.class,job.id())).isEqualTo(1);
            Files.write(output.resolve(labels.get(job.id())+".png"),storage.read(job.assetKey()).content());
        }
    }
}
