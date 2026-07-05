package com.triples.rougether.userapi.auth.client;

// 구글 idToken에서 검증·추출한 회원 식별정보. id는 sub(provider_user_id), email은 미제공/미동의 시 null.
public record GoogleUser(String id, String email) {
}
