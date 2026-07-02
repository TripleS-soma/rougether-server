package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import java.util.List;

// POST /api/v1/gacha/{id}/draw 응답. 뽑은 결과 목록 + 차감/전환 후 재화별 지갑 잔액(코인·다이아 모두).
public record GachaDrawResponse(List<DrawResult> results, List<WalletSummary> wallets) {

    // 한 번의 뽑기 결과. converted=true 면 중복 보유라 지급 대신 재화로 전환
    // (아이템 -> 다이아, 캐릭터 -> 코인. refundCurrencyType 이 전환된 재화).
    public record DrawResult(
            String rewardType,
            Long itemId,
            Long characterId,
            String name,
            String assetKey,
            String rarity,
            boolean converted,
            CurrencyType refundCurrencyType,
            Integer refundAmount) {
    }

    public record WalletSummary(CurrencyType currencyType, int balance) {
    }
}
