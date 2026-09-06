package com.triples.rougether.userapi.auth.client;

// 카카오에서 조회한 회원 식별정보. id는 카카오 회원번호(provider_user_id), email은 미동의/미제공 시 null.
// emailVerified 는 카카오가 그 이메일을 유효·인증됨(is_email_valid && is_email_verified)으로 표시했는지 —
// 같은 이메일 타 provider 계정 안내(409)는 인증된 이메일에만 적용해, 미인증 이메일로 타인 계정의 존재를 캐지 못하게 함.
public record KakaoUser(String id, String email, boolean emailVerified) {

    // 인증 여부를 모르는 축약 생성자(테스트·단순 호출용) — 보안 게이트용 플래그라 미인증(fail-closed)으로 둔다.
    public KakaoUser(String id, String email) {
        this(id, email, false);
    }
}
