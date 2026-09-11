package com.triples.rougether.lambdaai;

import java.time.Duration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;

final class FurnitureControlClient {
    private FurnitureControlClient() { }
    static LambdaClientBuilder builder() {
        // SDK 전체 제한만 늘려도 하위 HTTP socket의 기본 30초 제한은 남으므로 함께 설정함.
        // control 함수 실행 제한 60초보다 길게 기다리되 연결 획득/접속은 짧게 제한함.
        return LambdaClient.builder().httpClientBuilder(ApacheHttpClient.builder()
                .connectionAcquisitionTimeout(Duration.ofSeconds(5))
                .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(63)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(63)).apiCallTimeout(Duration.ofSeconds(65)).build());
    }
}
