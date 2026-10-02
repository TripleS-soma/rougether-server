package com.triples.rougether.domain.member.entity;

// refresh 토큰 폐기 사유. 재사용 판정(유예 대상 여부)과 운영 추적에 씀.
public enum RefreshTokenRevokeReason {
    // 정상 회전으로 다음 토큰에 자리를 넘김.
    ROTATED,
    // 응답 유실 재시도(유예)로 같은 family 의 새 토큰이 발급되며 밀려남.
    SUPERSEDED,
    // 로그아웃.
    LOGOUT,
    // 유예 밖 재사용 감지로 family(레거시는 회원 전체)가 폐기됨.
    REUSE,
    // 회원탈퇴.
    WITHDRAWAL;

    // 회전 계열 폐기 = 클라이언트가 응답을 못 받았을 수 있는 경우. 유예 판정 대상.
    public boolean isRotation() {
        return this == ROTATED || this == SUPERSEDED;
    }
}
