package com.triples.rougether.userapi.wallet.dto;

import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/me/wallets/histories 응답. 페이지네이션 규약({items, page, size, totalElements}) 적용.
public record WalletHistoryListResponse(
        List<WalletHistoryResponse> items,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "전체 이력 수 (currencyType·direction 필터 적용 시 필터된 결과 기준)", example = "42")
        long totalElements) {

    public record WalletHistoryResponse(
            @Schema(description = "이력 ID", example = "1")
            Long id,
            @Schema(description = "재화 종류. 허용값: COIN(코인 — 루틴/투두 완료·초대·가입 보상으로 획득, 뽑기에 사용), "
                    + "DIAMOND(다이아 — 뽑기 아이템 중복 전환으로 획득, 상점 구매에 사용)", example = "COIN")
            CurrencyType currencyType,
            @Schema(description = "증감액. 적립은 양수, 사용은 음수", example = "10")
            int amount,
            @Schema(description = "증감 사유. 허용값: ROUTINE_COMPLETE(루틴 완료 적립), TODO_COMPLETE(투두 완료 적립), "
                    + "SIGNUP_BONUS(가입 보너스 적립), GACHA_DUPLICATE_CONVERT(뽑기 중복 전환 적립), "
                    + "INVITE_REWARD(친구 초대 보상 적립), GACHA_DRAW(뽑기 실행 사용), SHOP_PURCHASE(상점 구매 사용)",
                    example = "ROUTINE_COMPLETE")
            WalletHistoryReason reason,
            @Schema(description = "증감 직후 잔액 스냅샷", example = "110")
            int balanceAfter,
            @Schema(description = "발생 시각 (UTC ISO-8601)", example = "2026-07-31T02:15:00Z")
            Instant createdAt) {

        public static WalletHistoryResponse from(WalletHistory history) {
            return new WalletHistoryResponse(history.getId(), history.getCurrencyType(),
                    history.getAmount(), history.getReason(), history.getBalanceAfter(),
                    history.getCreatedAt());
        }
    }
}
