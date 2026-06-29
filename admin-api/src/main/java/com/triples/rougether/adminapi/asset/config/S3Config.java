package com.triples.rougether.adminapi.asset.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

// S3 클라이언트. 자격증명은 DefaultCredentialsProvider(env / IAM role)로 자동 해결되므로
// access key 를 코드/설정에 두지 않는다.
@Configuration
@EnableConfigurationProperties(AssetProperties.class)
public class S3Config {

    @Bean
    S3Client s3Client(AssetProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.s3().region()))
                .build();
    }
}
