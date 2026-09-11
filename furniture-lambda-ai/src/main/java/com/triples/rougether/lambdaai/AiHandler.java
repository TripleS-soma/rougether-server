package com.triples.rougether.lambdaai;

import com.amazonaws.services.lambda.runtime.*;
import com.triples.rougether.furniture.lambda.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import java.io.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public class AiHandler implements RequestStreamHandler {
    private final FurnitureLambdaRunner runner;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public AiHandler() { this(AiRuntime.create()); }
    AiHandler(FurnitureLambdaRunner runner) { this.runner = runner; }
    @Override public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        byte[] payload = input.readNBytes(65_537);
        if (payload.length > 65_536) throw new IllegalArgumentException("SQS 이벤트 크기 초과");
        var records = JSON.readTree(payload).path("Records");
        if (!records.isArray() || records.size() != 1) throw new IllegalArgumentException("BatchSize=1 필수");
        var record = records.get(0);
        String id = record.path("messageId").asText();
        if (id.isBlank()) throw new IllegalArgumentException("SQS messageId 누락");
        boolean complete;
        try {
            var message = JSON.readValue(record.path("body").asText(), Message.class);
            UUID.fromString(message.jobId()); UUID.fromString(message.executionId());
            complete = runner.run(message, context.getAwsRequestId(), context::getRemainingTimeInMillis);
        } catch (RuntimeException e) {
            // 프롬프트, 이미지, 계정 정보 및 공급자 오류 본문은 로그에 남기지 않음.
            context.getLogger().log("furniture invocation failed requestId=" + context.getAwsRequestId() + "\n");
            complete = false;
        }
        JSON.writeValue(output, Map.of("batchItemFailures", complete ? List.of() : List.of(Map.of("itemIdentifier", id))));
    }
}
