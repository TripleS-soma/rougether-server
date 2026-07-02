package com.triples.rougether.userapi.wallet.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse;
import com.triples.rougether.userapi.wallet.service.WalletQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 내 재화 잔액 조회. 뽑기/상점 화면 상단 잔액 표시용.
@RestController
@RequestMapping("/api/v1/me/wallets")
public class WalletController {

    private final WalletQueryService walletQueryService;

    public WalletController(WalletQueryService walletQueryService) {
        this.walletQueryService = walletQueryService;
    }

    @GetMapping
    public WalletListResponse getWallets(@CurrentUser AuthUser user) {
        return walletQueryService.getWallets(user.id());
    }
}
