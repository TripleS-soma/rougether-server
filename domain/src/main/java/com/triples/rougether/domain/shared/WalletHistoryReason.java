package com.triples.rougether.domain.shared;

// 재화 증감 사유. wallet_histories.reason 에 문자열로 저장됨 (#253).
public enum WalletHistoryReason {
    ROUTINE_COMPLETE,        // 루틴 완료 보상 적립
    TODO_COMPLETE,           // 투두 완료 보상 적립
    SIGNUP_BONUS,            // 가입 보너스 적립
    GACHA_DUPLICATE_CONVERT, // 뽑기 중복 전환 적립(캐릭터 중복→코인, 아이템 중복→다이아)
    INVITE_REWARD,           // 친구 초대 보상 적립
    COBWEB_CLEAN,            // 장기 미접속 방 거미줄 청소 보상 적립
    GACHA_DRAW,              // 뽑기 실행 차감
    SHOP_PURCHASE            // 상점 아이템 구매 차감
}
