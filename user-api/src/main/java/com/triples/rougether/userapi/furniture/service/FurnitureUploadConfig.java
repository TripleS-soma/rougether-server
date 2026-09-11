package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.infra.assets.AssetProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(name = "furniture.upload.enabled", havingValue = "true")
public class FurnitureUploadConfig {
    @Bean(destroyMethod = "close") S3Presigner furnitureUploadPresigner(AssetProperties properties) {
        return S3Presigner.builder().region(Region.of(properties.s3().region())).build();
    }
}
