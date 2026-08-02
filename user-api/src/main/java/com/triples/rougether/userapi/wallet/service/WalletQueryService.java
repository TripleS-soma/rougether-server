package com.triples.rougether.userapi.wallet.service;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryDirection;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryListResponse;
import com.triples.rougether.userapi.wallet.dto.WalletHistoryListResponse.WalletHistoryResponse;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse;
import com.triples.rougether.userapi.wallet.dto.WalletListResponse.WalletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 지갑 잔액 조회. 지갑 row 가 아직 없는 재화(예: 다이아 미발급)도 잔액 0으로 내려 프론트가 그대로 표시한다.
@Service
public class WalletQueryService {

    private final UserWalletRepository userWalletRepository;
    private final WalletHistoryRepository walletHistoryRepository;

    public WalletQueryService(UserWalletRepository userWalletRepository,
                              WalletHistoryRepository walletHistoryRepository) {
        this.userWalletRepository = userWalletRepository;
        this.walletHistoryRepository = walletHistoryRepository;
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

    // 재화 증감 이력 조회(#253). currencyType·direction 은 optional 필터, 최신순 고정 정렬.
    @Transactional(readOnly = true)
    public WalletHistoryListResponse getHistories(Long userId, CurrencyType currencyType,
                                                  WalletHistoryDirection direction, int page, int size) {
        Boolean earn = direction == null ? null : direction == WalletHistoryDirection.EARN;
        Page<WalletHistory> histories = walletHistoryRepository.findHistories(
                userId, currencyType, earn, PageRequest.of(page, size));
        return new WalletHistoryListResponse(
                histories.getContent().stream().map(WalletHistoryResponse::from).toList(),
                page, size, histories.getTotalElements());
    }
}
