package com.triples.rougether.userapi.wallet.service;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse.WalletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 지갑 잔액 조회. 지갑 row 가 아직 없는 재화(예: 다이아 미발급)도 잔액 0으로 내려 프론트가 그대로 표시한다.
@Service
public class WalletQueryService {

    private final UserWalletRepository userWalletRepository;

    public WalletQueryService(UserWalletRepository userWalletRepository) {
        this.userWalletRepository = userWalletRepository;
    }

    @Transactional(readOnly = true)
    public WalletListResponse getWallets(Long userId) {
        Map<CurrencyType, Integer> balances = userWalletRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(UserWallet::getCurrencyType, UserWallet::getBalance));

        List<WalletResponse> items = Arrays.stream(CurrencyType.values())
                .map(type -> new WalletResponse(type, balances.getOrDefault(type, 0)))
                .toList();
        return new WalletListResponse(items);
    }
}
