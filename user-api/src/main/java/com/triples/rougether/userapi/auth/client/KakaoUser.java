package com.triples.rougether.userapi.auth.client;

// 카카오에서 조회한 회원 식별정보. id는 카카오 회원번호(provider_user_id), email은 미동의/미제공 시 null.
public record KakaoUser(String id, String email) {
}
