package com.triples.rougether.userapi.global.config;

import java.util.List;

// 보안 CORS 필터와 WebSocket 핸들러가 같은 handshake 출처 정책을 사용한다.
public record ChatWebSocketOrigins(List<String> allowedOrigins) {
}
