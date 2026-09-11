package com.triples.rougether.lambdapreprocess;

import com.amazonaws.services.lambda.runtime.*;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol.Reply;
import com.triples.rougether.preprocessing.VipsPhotoPreprocessor;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

public class PreprocessHandler implements RequestStreamHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PreprocessProcessor processor;
    private static class Holder {
        static final PreprocessProcessor PROCESSOR = create();
        static PreprocessProcessor create() {
            var s3 = S3Client.builder().httpClientBuilder(ApacheHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallAttemptTimeout(Duration.ofSeconds(35)).apiCallTimeout(Duration.ofSeconds(60)).build()).build();
            var lambda = LambdaClient.builder().httpClientBuilder(ApacheHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(63)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallAttemptTimeout(Duration.ofSeconds(63)).apiCallTimeout(Duration.ofSeconds(65)).build()).build();
            String function = Objects.requireNonNull(System.getenv("CONTROL_FUNCTION_NAME"));
            return new PreprocessProcessor(s3, command -> {
                var result = lambda.invoke(r -> r.functionName(function).payload(SdkBytes.fromByteArray(JSON.writeValueAsBytes(command))));
                if (result.functionError() != null || result.payload() == null) throw new IllegalStateException("DB 제어 실패");
                return JSON.readValue(result.payload().asByteArray(), Reply.class);
            }, new VipsPhotoPreprocessor()::prepare, Objects.requireNonNull(System.getenv("ASSET_S3_BUCKET")));
        }
    }
    public PreprocessHandler() { this(Holder.PROCESSOR); }
    PreprocessHandler(PreprocessProcessor processor) { this.processor = processor; }

    @Override public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        byte[] bytes = input.readNBytes(262_145);
        if (bytes.length > 262_144) throw new IllegalArgumentException("SQS 이벤트 크기 초과");
        var event = JSON.readTree(bytes);
        var messages = event.path("Records");
        if (!messages.isArray() || messages.size() != 1) throw new IllegalArgumentException("BatchSize=1 필수");
        var message = messages.get(0);
        String id = message.path("messageId").asString();
        if (id == null || id.isBlank()) throw new IllegalArgumentException("SQS ID 누락");
        List<Map<String, String>> failures = new ArrayList<>();
        try {
            var body = JSON.readTree(message.path("body").asString());
            if (!"s3:TestEvent".equals(body.path("Event").asString())) {
                var records = body.path("Records");
                if (!records.isArray() || records.isEmpty() || records.size() > 10) throw new IllegalArgumentException("S3 이벤트 오류");
                for (var record : records) {
                    if (context.getRemainingTimeInMillis() < 150_000) throw new IllegalStateException("실행 시간 부족");
                    if (!"aws:s3".equals(record.path("eventSource").asString())
                            || !"ObjectCreated:Put".equals(record.path("eventName").asString())) throw new IllegalArgumentException("S3 이벤트 종류 오류");
                    var s3 = record.path("s3");
                    processor.process(s3.path("bucket").path("name").asString(),
                            URLDecoder.decode(s3.path("object").path("key").asString(), StandardCharsets.UTF_8),
                            s3.path("object").path("versionId").asString());
                }
            }
        } catch (Exception e) {
            context.getLogger().log("PREPROCESS_FAILED " + id + " " + e.getClass().getSimpleName());
            failures.add(Map.of("itemIdentifier", id));
        }
        JSON.writeValue(output, Map.of("batchItemFailures", failures));
    }
}
