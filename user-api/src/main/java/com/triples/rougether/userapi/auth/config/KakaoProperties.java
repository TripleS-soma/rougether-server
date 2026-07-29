package com.triples.rougether.userapi.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// adminKey는 회원탈퇴 시 서버-투-서버 unlink용 시크릿(환경변수 주입, 커밋 금지).
// 미설정이면 KakaoUnlinkClient가 502로 실패함(fail-closed).
@ConfigurationProperties("kakao")
public record KakaoProperties(String apiBaseUrl, Long appId, String adminKey) {
}
