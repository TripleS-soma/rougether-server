package com.triples.rougether.userapi.wallet.web;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryDirection;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryListResponse;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 내 재화 잔액 조회. 뽑기/상점 화면 상단 잔액 표시용.
@Tag(name = "Wallet", description = "재화 지갑 관련 API")
@RestController
@RequestMapping("/api/v1/me/wallets")
public class WalletController {

    private final WalletQueryService walletQueryService;

    public WalletController(WalletQueryService walletQueryService) {
        this.walletQueryService = walletQueryService;
    }

    @Operation(summary = "내 재화 잔액 조회",
            description = "로그인한 회원의 모든 재화(코인·다이아) 잔액을 반환합니다. 아직 발급되지 않은 지갑은 잔액 0으로 내려줍니다. "
                    + "코인(COIN)은 루틴 완료(+10)·투두 완료(+5)·뽑기 캐릭터 중복 전환(+200)으로 적립되고 "
                    + "뽑기 실행(POST /api/v1/gacha/{id}/draw) 비용으로 사용합니다. "
                    + "다이아(DIAMOND)는 뽑기 아이템 중복 전환(+30)으로 적립되고 "
                    + "상점 아이템 구매(POST /api/v1/items/{itemId}/purchase)에 사용합니다.")
    @GetMapping
    public WalletListResponse getWallets(@CurrentUser AuthUser user) {
        return walletQueryService.getWallets(user.id());
    }

    @Operation(summary = "내 재화 증감 이력 조회",
            description = "로그인한 회원의 재화 획득·사용 이력을 최신순으로 반환합니다. "
                    + "적립은 amount 양수, 사용은 음수이며 balanceAfter 는 증감 직후 잔액입니다. "
                    + "루틴/투두 완료를 취소하면 해당 획득 이력은 삭제되어 목록에서 사라집니다. "
                    + "미지정 시 page=0, size=20 으로 조회합니다.")
    @GetMapping("/histories")
    public WalletHistoryListResponse getHistories(
            @CurrentUser AuthUser user,
            @Parameter(description = "재화 종류 필터 (선택). 허용값: COIN, DIAMOND")
            @RequestParam(required = false) CurrencyType currencyType,
            @Parameter(description = "증감 방향 필터 (선택). 허용값: EARN(적립), SPEND(사용)")
            @RequestParam(required = false) WalletHistoryDirection direction,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") @Min(1) int size) {
        return walletQueryService.getHistories(user.id(), currencyType, direction, page, size);
    }
}
