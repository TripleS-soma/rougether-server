package com.triples.rougether.userapi.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 브라우저 클라이언트(웹앱 app.rougether.com, 로컬 expo 웹)의 허용 출처. 네이티브 앱은 CORS와 무관함.
// 환경변수 CORS_ALLOWED_ORIGINS(쉼표 구분)로 덮어씀. 비면 브라우저 요청은 전부 차단됨(fail-closed).
@ConfigurationProperties("cors")
public record CorsProperties(List<String> allowedOrigins) {

    public List<String> allowedOrigins() {
        return allowedOrigins == null ? List.of() : allowedOrigins;
    }
}
