package com.triples.rougether.userapi.global.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 에셋 S3 설정. 자격증명은 여기 두지 않고 AWS 기본 체인(env / IAM role)으로 해결한다.
@ConfigurationProperties("asset")
public record AssetProperties(S3 s3) {

    public record S3(String bucket, String region) {
    }
}
