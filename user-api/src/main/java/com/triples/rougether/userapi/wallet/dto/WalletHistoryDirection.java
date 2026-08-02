package com.triples.rougether.userapi.wallet.dto;

// GET /api/v1/me/wallets/histories 의 증감 방향 필터. 저장 컬럼이 아니라 amount 부호로 판정하는 조회 전용 값.
public enum WalletHistoryDirection {
    EARN,  // 적립(amount 양수)
    SPEND  // 사용(amount 음수)
}
