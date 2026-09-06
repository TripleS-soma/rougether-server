package com.triples.rougether.userapi.auth.client;

// 구글 idToken에서 검증·추출한 회원 식별정보. id는 sub(provider_user_id), email은 미제공/미동의 시 null.
// emailVerified 는 idToken 의 email_verified 클레임 — 같은 이메일 타 provider 계정 안내(409)는 인증된 이메일에만 적용함.
public record GoogleUser(String id, String email, boolean emailVerified) {

    // 인증 여부를 모르는 축약 생성자(테스트·단순 호출용) — 보안 게이트용 플래그라 미인증(fail-closed)으로 둔다.
    public GoogleUser(String id, String email) {
        this(id, email, false);
    }
}
