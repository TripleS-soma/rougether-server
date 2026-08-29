package com.triples.rougether.domain.invite.entity;

// 초대 링크 종류. FRIEND = 개인 초대코드 랜딩(/i/{code}), HOUSE = 집 초대코드 랜딩(/h/{code}).
// 두 코드는 네임스페이스가 겹칠 수 있어(발급기가 서로의 유일성을 보지 않음) 링크 path 로 종류를 구분한다.
public enum InviteLinkType {
    FRIEND,
    HOUSE
}
