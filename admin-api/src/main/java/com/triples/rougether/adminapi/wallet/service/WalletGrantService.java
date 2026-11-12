package com.triples.rougether.adminapi.wallet.service;

import com.triples.rougether.adminapi.wallet.dto.WalletGrantRequest;
import com.triples.rougether.adminapi.wallet.dto.WalletGrantResponse;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 개발/QA용 재화 지급. 지갑이 없으면 발급 후 적립한다.
@Service
public class WalletGrantService {

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final WalletHistoryRepository walletHistoryRepository;

    public WalletGrantService(UserRepository userRepository, UserWalletRepository userWalletRepository,
                              WalletHistoryRepository walletHistoryRepository) {
        this.userRepository = userRepository;
        this.userWalletRepository = userWalletRepository;
        this.walletHistoryRepository = walletHistoryRepository;
    }

    @Transactional
    public WalletGrantResponse grant(Long userId, WalletGrantRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 회원입니다: " + userId));
        // 유저 소비 트랜잭션(구매/뽑기)과 경합해도 잔액이 유실되지 않게 행 락으로 적립한다.
        UserWallet wallet = userWalletRepository
                .findWithLockByUserIdAndCurrencyType(userId, request.currencyType())
                .orElse(null);
        boolean created = wallet == null;
        if (created) {
            wallet = userWalletRepository.save(UserWallet.create(user, request.currencyType()));
        }
        wallet.add(request.amount());
        // 잔액 적립과 원장을 같은 트랜잭션으로 저장함. 원장 저장 실패 시 지갑 발급과 적립도 롤백됨.
        walletHistoryRepository.save(WalletHistory.record(
                user, request.currencyType(), request.amount(), WalletHistoryReason.ADMIN_GRANT,
                wallet.getBalance(), null, null));
        return new WalletGrantResponse(
                userId, request.currencyType(), request.amount(), wallet.getBalance(), created);
    }
}
