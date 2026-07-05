package com.triples.rougether.domain.house.entity;

// 참여는 즉시가입이라 PENDING 없음. LEFT 는 재가입(재활성화) 가능, KICKED 는 재가입 불가.
public enum HouseMemberStatus {
    ACTIVE,  // 활동 중
    LEFT,    // 탈퇴 (재가입 가능)
    KICKED   // 강퇴 (재가입 불가)
}
