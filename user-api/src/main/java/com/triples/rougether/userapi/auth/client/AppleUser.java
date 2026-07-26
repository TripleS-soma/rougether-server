package com.triples.rougether.userapi.auth.client;

// 애플 identityToken에서 검증·추출한 회원 식별정보. id는 sub(provider_user_id),
// email은 미제공/재로그인 시 null이거나 private relay 주소일 수 있음.
public record AppleUser(String id, String email) {
}
