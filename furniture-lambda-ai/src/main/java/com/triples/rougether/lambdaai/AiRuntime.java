package com.triples.rougether.lambdaai;

import com.triples.rougether.furniture.ai.OpenAiFurnitureClient;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.lambda.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Reply;
import com.triples.rougether.furniture.service.FurnitureImages;
import com.triples.rougether.infra.assets.*;
import com.triples.rougether.infra.llm.LlmProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import tools.jackson.databind.json.JsonMapper;

final class AiRuntime {
    private AiRuntime() { }
    static FurnitureLambdaRunner create() {
        // Spring/JPA를 기동하지 않음. DB 접속 정보도 이 함수에는 주입하지 않음.
        var binder = Binder.get(new StandardEnvironment());
        var config = binder.bindOrCreate("furniture.generation", FurnitureGenerationProperties.class);
        var llm = binder.bindOrCreate("llm", LlmProperties.class);
        String key;
        try (var ssm = SsmClient.create()) {
            key = ssm.getParameter(r -> r.name(required("LLM_API_KEY_PARAMETER")).withDecryption(true)).parameter().value();
        }
        var configured = new LlmProperties(llm.baseUrl(), llm.model(), key, llm.timeout(), llm.temperature(), llm.maxTokens(),
                llm.jsonMode(), llm.reasoningEffort(), llm.maxRetries(), llm.retryBackoff(), llm.embeddingModel(), llm.embeddingDimensions());
        var assets = new AssetProperties(new AssetProperties.S3(required("ASSET_S3_BUCKET"), required("AWS_REGION"), false));
        var storage = new S3AssetStorageService(S3Client.create(), assets);
        var executor = new FurnitureLambdaStage(new OpenAiFurnitureClient(configured, config), new FurnitureImages(), storage, config);
        var client = FurnitureControlClient.builder().build();
        String function = required("CONTROL_FUNCTION_NAME");
        var json = JsonMapper.builder().build();
        return new FurnitureLambdaRunner(command -> {
            byte[] body = json.writeValueAsBytes(command);
            var response = client.invoke(r -> r.functionName(function).payload(SdkBytes.fromByteArray(body)));
            if (response.statusCode() != 200 || response.functionError() != null) throw new IllegalStateException("DB 제어 호출 실패");
            return json.readValue(response.payload().asByteArray(), Reply.class);
        }, executor::execute, config.timeout());
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " 설정 필요");
        return value;
    }
}
