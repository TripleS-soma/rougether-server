package com.triples.rougether.infra.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asset")
public record AssetProperties(S3 s3, String publicBaseUrl) {

    public record S3(String bucket, String region) {
    }
}
