package com.triples.rougether.userapi.wallet.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
            description = "로그인한 회원의 모든 재화(코인·다이아) 잔액을 반환합니다. 아직 발급되지 않은 지갑은 잔액 0으로 내려줍니다.")
    @GetMapping
    public WalletListResponse getWallets(@CurrentUser AuthUser user) {
        return walletQueryService.getWallets(user.id());
    }
}
