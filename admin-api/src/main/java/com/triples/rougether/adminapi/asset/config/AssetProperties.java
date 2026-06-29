package com.triples.rougether.adminapi.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 에셋 설정. 자격증명은 여기 두지 않고 AWS 기본 체인(env / IAM role)으로 해결한다.
// publicBaseUrl: 공개 이미지 base (현재 S3, 추후 CloudFront/CDN 으로 교체) — key 와 조합해 URL 을 만든다.
@ConfigurationProperties("asset")
public record AssetProperties(S3 s3, String publicBaseUrl) {

    public record S3(String bucket, String region) {
    }
}
