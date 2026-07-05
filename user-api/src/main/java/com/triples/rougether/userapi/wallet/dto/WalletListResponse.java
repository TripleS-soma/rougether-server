package com.triples.rougether.userapi.wallet.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/me/wallets 응답. 모든 재화를 항상 내려주고, 지갑이 없는 재화는 잔액 0.
public record WalletListResponse(List<WalletResponse> items) {

    public record WalletResponse(
            @Schema(description = "재화 종류. 허용값: COIN(코인 — 루틴 완료 +10·투두 완료 +5·뽑기 캐릭터 중복 전환 +200 으로 획득, "
                    + "뽑기 비용으로 사용), DIAMOND(다이아 — 뽑기 아이템 중복 전환 +30 으로 획득, 상점 구매에 사용)",
                    example = "COIN")
            CurrencyType currencyType,
            @Schema(description = "잔액 (지갑 미발급 재화는 0. 가입 시 코인 지갑만 생성되고 다이아 지갑은 최초 적립 시 발급)",
                    example = "5600")
            int balance) {
    }
}
