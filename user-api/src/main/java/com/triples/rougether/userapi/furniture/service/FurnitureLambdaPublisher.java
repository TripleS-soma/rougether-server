package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.furniture.lambda.FurnitureLambdaOutbox;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Message;
import com.triples.rougether.furniture.service.FurnitureGenerationWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

/** API는 작은 SQS 메시지 전송과 정리만 수행함. AI 실행은 하지 않음. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "furniture.lambda.dispatch-enabled", havingValue = "true")
@Slf4j
public class FurnitureLambdaPublisher {
    private final FurnitureLambdaOutbox outbox;
    private final FurnitureGenerationWorker worker;
    private final SqsClient sqs;
    private final String queueUrl;
    private final JsonMapper json = JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired
    public FurnitureLambdaPublisher(FurnitureLambdaOutbox outbox, FurnitureGenerationWorker worker,
            @Value("${furniture.lambda.queue-url}") String queueUrl) {
        this(outbox, worker, queueUrl, SqsClient.builder().overrideConfiguration(
                software.amazon.awssdk.core.client.config.ClientOverrideConfiguration.builder()
                        .apiCallTimeout(java.time.Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(java.time.Duration.ofSeconds(3)).build()).build());
    }
    FurnitureLambdaPublisher(FurnitureLambdaOutbox outbox, FurnitureGenerationWorker worker, String queueUrl, SqsClient sqs) {
        this.outbox = outbox; this.worker = worker; this.queueUrl = queueUrl; this.sqs = sqs;
    }
    @Bean(destroyMethod = "close") SqsClient furnitureSqsClient() { return sqs; }
    @Scheduled(fixedDelayString = "${furniture.lambda.dispatch-delay:2000}")
    public void publish() {
        for (String id : outbox.pending()) {
            try {
                var dispatch = outbox.reserve(id);
                if (dispatch == null) continue;
                String body = json.writeValueAsString(new Message(dispatch.jobId(), dispatch.executionId()));
                sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(body));
                outbox.sent(dispatch);
            } catch (RuntimeException e) { log.warn("가구 SQS 전달 보류 execution={}", id); }
        }
    }
    @Scheduled(fixedDelay = 60_000)
    public void maintain() { worker.maintain(); }
}
